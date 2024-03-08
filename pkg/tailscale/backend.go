// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package main

import (
	"context"
	"fmt"
	"log"

	jnipkg "github.com/tailscale/tailscale-android/pkg/jni"
	"github.com/tailscale/tailscale-android/pkg/localapiservice"
	"tailscale.com/hostinfo"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnlocal"
	"tailscale.com/ipn/localapi"
	"tailscale.com/logtail"
	"tailscale.com/net/dns"
	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
	"tailscale.com/net/tsdial"
	"tailscale.com/paths"
	"tailscale.com/tsd"
	"tailscale.com/types/logger"
	"tailscale.com/types/logid"
	"tailscale.com/types/netmap"
	"tailscale.com/wgengine"
	"tailscale.com/wgengine/netstack"
	"tailscale.com/wgengine/router"
)

import "C"

type BackendState struct {
	State        ipn.State
	NetworkMap   *netmap.NetworkMap
	LostInternet bool
}

type backend struct {
	engine     wgengine.Engine
	backend    *ipnlocal.LocalBackend
	sys        *tsd.System
	devices    *multiTUN
	settings   settingsFunc
	lastCfg    *router.Config
	lastDNSCfg *dns.OSConfig
	netMon     *netmon.Monitor

	logIDPublic logid.PublicID
	logger      *logtail.Logger

	// avoidEmptyDNS controls whether to use fallback nameservers
	// when no nameservers are provided by Tailscale.
	avoidEmptyDNS bool

	jvm    *jnipkg.JVM
	appCtx jnipkg.Object
}

type settingsFunc func(*router.Config, *dns.OSConfig) error

func (a *App) runBackend(ctx context.Context) error {
	appDir, err := dataDir()
	if err != nil {
		fatalErr(err)
	}
	paths.AppSharedDir.Store(appDir)
	hostinfo.SetOSVersion(a.osVersion())
	if !googleSignInEnabled() {
		hostinfo.SetPackage("nogoogle")
	}
	deviceModel := a.modelName()
	if a.isChromeOS() {
		deviceModel = "ChromeOS: " + deviceModel
	}
	hostinfo.SetDeviceModel(deviceModel)

	type configPair struct {
		rcfg *router.Config
		dcfg *dns.OSConfig
	}
	configs := make(chan configPair)
	configErrs := make(chan error)
	b, err := newBackend(appDir, a.jvm, a.appCtx, a.store, func(rcfg *router.Config, dcfg *dns.OSConfig) error {
		if rcfg == nil {
			return nil
		}
		configs <- configPair{rcfg, dcfg}
		return <-configErrs
	})
	if err != nil {
		return err
	}
	a.logIDPublicAtomic.Store(&b.logIDPublic)
	a.backend = b.backend
	defer b.CloseTUNs()

	h := localapi.NewHandler(b.backend, log.Printf, b.sys.NetMon.Get(), *a.logIDPublicAtomic.Load())
	h.PermitRead = true
	h.PermitWrite = true
	a.localAPI = localapiservice.New(h)

	// Share the localAPI with the JNI shim
	//localapiservice.SetLocalAPIService(a.localAPI)
	localapiservice.ConfigureShim(a.jvm, a.appCtx, a.localAPI, b.backend)

	// Contrary to the documentation for VpnService.Builder.addDnsServer,
	// ChromeOS doesn't fall back to the underlying network nameservers if
	// we don't provide any.
	b.avoidEmptyDNS = a.isChromeOS()

	var (
		cfg     configPair
		state   BackendState
		service jnipkg.Object // of IPNService
	)
	for {
		select {
		case c := <-configs:
			cfg = c
			if b == nil || service == 0 || cfg.rcfg == nil {
				configErrs <- nil
				break
			}
			configErrs <- b.updateTUN(service, cfg.rcfg, cfg.dcfg)
		case s := <-onVPNRequested:
			jnipkg.Do(a.jvm, func(env *jnipkg.Env) error {
				if jnipkg.IsSameObject(env, s, service) {
					// We already have a reference.
					jnipkg.DeleteGlobalRef(env, s)
					return nil
				}
				if service != 0 {
					jnipkg.DeleteGlobalRef(env, service)
				}
				netns.SetAndroidProtectFunc(func(fd int) error {
					return jnipkg.Do(a.jvm, func(env *jnipkg.Env) error {
						// Call https://developer.android.com/reference/android/net/VpnService#protect(int)
						// to mark fd as a socket that should bypass the VPN and use the underlying network.
						cls := jnipkg.GetObjectClass(env, s)
						m := jnipkg.GetMethodID(env, cls, "protect", "(I)Z")
						ok, err := jnipkg.CallBooleanMethod(env, s, m, jnipkg.Value(fd))
						// TODO(bradfitz): return an error back up to netns if this fails, once
						// we've had some experience with this and analyzed the logs over a wide
						// range of Android phones. For now we're being paranoid and conservative
						// and do the JNI call to protect best effort, only logging if it fails.
						// The risk of returning an error is that it breaks users on some Android
						// versions even when they're not using exit nodes. I'd rather the
						// relatively few number of exit node users file bug reports if Tailscale
						// doesn't work and then we can look for this log print.
						if err != nil || !ok {
							log.Printf("[unexpected] VpnService.protect(%d) = %v, %v", fd, ok, err)
						}
						return nil // even on error. see big TODO above.
					})
				})
				log.Printf("onVPNRequested: rebind required")
				// TODO(catzkorn): When we start the android application
				// we bind sockets before we have access to the VpnService.protect()
				// function which is needed to avoid routing loops. When we activate
				// the service we get access to the protect, but do not retrospectively
				// protect the sockets already opened, which breaks connectivity.
				// As a temporary fix, we rebind and protect the magicsock.Conn on connect
				// which restores connectivity.
				// See https://github.com/tailscale/corp/issues/13814
				b.backend.DebugRebind()

				service = s
				return nil
			})
			if m := state.NetworkMap; m != nil {
				// TODO
			}
			if cfg.rcfg != nil && state.State >= ipn.Starting {
				if err := b.updateTUN(service, cfg.rcfg, cfg.dcfg); err != nil {
					log.Printf("VPN update failed: %v", err)
					notifyVPNClosed()
				}
			}
		case s := <-onDisconnect:
			b.CloseTUNs()
			jnipkg.Do(a.jvm, func(env *jnipkg.Env) error {
				defer jnipkg.DeleteGlobalRef(env, s)
				if jnipkg.IsSameObject(env, service, s) {
					netns.SetAndroidProtectFunc(nil)
					jnipkg.DeleteGlobalRef(env, service)
					service = 0
				}
				return nil
			})
			if state.State >= ipn.Starting {
				notifyVPNClosed()
			}
		case <-onDNSConfigChanged:
			if b != nil {
				go b.NetworkChanged()
			}
		}
	}
}

func newBackend(dataDir string, jvm *jnipkg.JVM, appCtx jnipkg.Object, store *stateStore,
	settings settingsFunc) (*backend, error) {

	sys := new(tsd.System)
	sys.Set(store)

	logf := logger.RusagePrefixLog(log.Printf)
	b := &backend{
		jvm:      jvm,
		devices:  newTUNDevices(),
		settings: settings,
		appCtx:   appCtx,
	}
	var logID logid.PrivateID
	logID.UnmarshalText([]byte("dead0000dead0000dead0000dead0000dead0000dead0000dead0000dead0000"))
	storedLogID, err := store.read(logPrefKey)
	// In all failure cases we ignore any errors and continue with the dead value above.
	if err != nil || storedLogID == nil {
		// Read failed or there was no previous log id.
		newLogID, err := logid.NewPrivateID()
		if err == nil {
			logID = newLogID
			enc, err := newLogID.MarshalText()
			if err == nil {
				store.write(logPrefKey, enc)
			}
		}
	} else {
		logID.UnmarshalText([]byte(storedLogID))
	}

	netMon, err := netmon.New(logf)
	if err != nil {
		log.Printf("netmon.New: %w", err)
	}
	b.netMon = netMon
	b.setupLogs(dataDir, logID, logf)
	dialer := new(tsdial.Dialer)
	cb := &router.CallbackRouter{
		SetBoth:           b.setCfg,
		SplitDNS:          false,
		GetBaseConfigFunc: b.getDNSBaseConfig,
	}
	engine, err := wgengine.NewUserspaceEngine(logf, wgengine.Config{
		Tun:          b.devices,
		Router:       cb,
		DNS:          cb,
		Dialer:       dialer,
		SetSubsystem: sys.Set,
		NetMon:       b.netMon,
	})
	if err != nil {
		return nil, fmt.Errorf("runBackend: NewUserspaceEngine: %v", err)
	}
	sys.Set(engine)
	b.logIDPublic = logID.Public()
	ns, err := netstack.Create(logf, sys.Tun.Get(), engine, sys.MagicSock.Get(), dialer, sys.DNSManager.Get(), sys.ProxyMapper(), nil)
	if err != nil {
		return nil, fmt.Errorf("netstack.Create: %w", err)
	}
	sys.Set(ns)
	ns.ProcessLocalIPs = false // let Android kernel handle it; VpnBuilder sets this up
	ns.ProcessSubnets = true   // for Android-being-an-exit-node support
	sys.NetstackRouter.Set(true)
	if w, ok := sys.Tun.GetOK(); ok {
		w.Start()
	}
	lb, err := ipnlocal.NewLocalBackend(logf, logID.Public(), sys, 0)
	if err != nil {
		engine.Close()
		return nil, fmt.Errorf("runBackend: NewLocalBackend: %v", err)
	}
	if err := ns.Start(lb); err != nil {
		return nil, fmt.Errorf("startNetstack: %w", err)
	}
	if b.logger != nil {
		lb.SetLogFlusher(b.logger.StartFlush)
	}
	b.engine = engine
	b.backend = lb
	b.sys = sys
	return b, nil
}
