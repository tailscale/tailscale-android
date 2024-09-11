// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"runtime/debug"
	"sync"
	"sync/atomic"

	"tailscale.com/drive/driveimpl"
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

type App struct {
	dataDir string

	// enables direct file mode for the taildrop manager
	directFileRoot string

	// appCtx is a global reference to the com.tailscale.ipn.App instance.
	appCtx AppContext

	store             *stateStore
	policyStore       *syspolicyHandler
	logIDPublicAtomic atomic.Pointer[logid.PublicID]

	localAPIHandler http.Handler
	backend         *ipnlocal.LocalBackend
	ready           sync.WaitGroup
}

func start(dataDir, directFileRoot string, appCtx AppContext) Application {
	defer func() {
		if p := recover(); p != nil {
			log.Printf("panic in Start %s: %s", p, debug.Stack())
			panic(p)
		}
	}()

	initLogging(appCtx)
	// Set XDG_CACHE_HOME to make os.UserCacheDir work.
	if _, exists := os.LookupEnv("XDG_CACHE_HOME"); !exists {
		cachePath := filepath.Join(dataDir, "cache")
		os.Setenv("XDG_CACHE_HOME", cachePath)
	}
	// Set XDG_CONFIG_HOME to make os.UserConfigDir work.
	if _, exists := os.LookupEnv("XDG_CONFIG_HOME"); !exists {
		cfgPath := filepath.Join(dataDir, "config")
		os.Setenv("XDG_CONFIG_HOME", cfgPath)
	}
	// Set HOME to make os.UserHomeDir work.
	if _, exists := os.LookupEnv("HOME"); !exists {
		os.Setenv("HOME", dataDir)
	}

	return newApp(dataDir, directFileRoot, appCtx)
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

	appCtx AppContext
}

type settingsFunc func(*router.Config, *dns.OSConfig) error

func (a *App) runBackend(ctx context.Context) error {
	paths.AppSharedDir.Store(a.dataDir)
	hostinfo.SetOSVersion(a.osVersion())
	if !a.appCtx.IsPlayVersion() {
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
	b, err := a.newBackend(a.dataDir, a.directFileRoot, a.appCtx, a.store, func(rcfg *router.Config, dcfg *dns.OSConfig) error {
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

	h := localapi.NewHandler(b.backend, log.Printf, *a.logIDPublicAtomic.Load())
	h.PermitRead = true
	h.PermitWrite = true
	a.localAPIHandler = h

	a.ready.Done()

	// Contrary to the documentation for VpnService.Builder.addDnsServer,
	// ChromeOS doesn't fall back to the underlying network nameservers if
	// we don't provide any.
	b.avoidEmptyDNS = a.isChromeOS()

	var (
		cfg        configPair
		state      ipn.State
		networkMap *netmap.NetworkMap
	)

	stateCh := make(chan ipn.State)
	netmapCh := make(chan *netmap.NetworkMap)
	go b.backend.WatchNotifications(ctx, ipn.NotifyInitialNetMap|ipn.NotifyInitialPrefs|ipn.NotifyInitialState, func() {}, func(notify *ipn.Notify) bool {
		if notify.State != nil {
			stateCh <- *notify.State
		}
		if notify.NetMap != nil {
			netmapCh <- notify.NetMap
		}
		return true
	})
	for {
		select {
		case s := <-stateCh:
			state = s
			if cfg.rcfg != nil && state >= ipn.Starting && vpnService.service != nil {
				// On state change, check if there are router or config changes requiring an update to VPNBuilder
				if err := b.updateTUN(vpnService.service, cfg.rcfg, cfg.dcfg); err != nil {
					if errors.Is(err, errMultipleUsers) {
						// TODO: surface error to user
					}
					log.Printf("VPN update failed: %v", err)

					mp := new(ipn.MaskedPrefs)
					mp.WantRunning = false
					mp.WantRunningSet = true

					_, err := a.EditPrefs(*mp)
					if err != nil {
						log.Printf("localapi edit prefs error %v", err)
					}

					b.lastCfg = nil
					b.CloseTUNs()
				}
			}
		case n := <-netmapCh:
			networkMap = n
		case c := <-configs:
			cfg = c
			if b == nil || vpnService.service == nil || cfg.rcfg == nil {
				configErrs <- nil
				break
			}
			configErrs <- b.updateTUN(vpnService.service, cfg.rcfg, cfg.dcfg)
		case s := <-onVPNRequested:
			if vpnService.service != nil && vpnService.service.ID() == s.ID() {
				// Still the same VPN instance, do nothing
				break
			}
			netns.SetAndroidProtectFunc(func(fd int) error {
				if !s.Protect(int32(fd)) {
					// TODO(bradfitz): return an error back up to netns if this fails, once
					// we've had some experience with this and analyzed the logs over a wide
					// range of Android phones. For now we're being paranoid and conservative
					// and do the JNI call to protect best effort, only logging if it fails.
					// The risk of returning an error is that it breaks users on some Android
					// versions even when they're not using exit nodes. I'd rather the
					// relatively few number of exit node users file bug reports if Tailscale
					// doesn't work and then we can look for this log print.
					log.Printf("[unexpected] VpnService.protect(%d) returned false", fd)
				}
				return nil // even on error. see big TODO above.
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

			vpnService.service = s

			if networkMap != nil {
				// TODO
			}
			if cfg.rcfg != nil && state >= ipn.Starting {
				if err := b.updateTUN(vpnService.service, cfg.rcfg, cfg.dcfg); err != nil {
					log.Printf("VPN update failed: %v", err)
					vpnService.service.Close()
					b.lastCfg = nil
					b.CloseTUNs()
				}
			}
		case s := <-onDisconnect:
			b.CloseTUNs()
			if vpnService.service != nil && vpnService.service.ID() == s.ID() {
				netns.SetAndroidProtectFunc(nil)
				vpnService.service = nil
			}
		case i := <-onDNSConfigChanged:
			if b != nil {
				go b.NetworkChanged(i)
			}
		}
	}
}

func (a *App) newBackend(dataDir, directFileRoot string, appCtx AppContext, store *stateStore,
	settings settingsFunc) (*backend, error) {

	sys := new(tsd.System)
	sys.Set(store)

	logf := logger.RusagePrefixLog(log.Printf)
	b := &backend{
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
	b.setupLogs(dataDir, logID, logf, sys.HealthTracker())
	dialer := new(tsdial.Dialer)
	vf := &VPNFacade{
		SetBoth:           b.setCfg,
		GetBaseConfigFunc: b.getDNSBaseConfig,
	}
	engine, err := wgengine.NewUserspaceEngine(logf, wgengine.Config{
		Tun:            b.devices,
		Router:         vf,
		DNS:            vf,
		ReconfigureVPN: vf.ReconfigureVPN,
		Dialer:         dialer,
		SetSubsystem:   sys.Set,
		NetMon:         b.netMon,
		HealthTracker:  sys.HealthTracker(),
		DriveForLocal:  driveimpl.NewFileSystemForLocal(logf),
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
	lb.SetDirectFileRoot(directFileRoot)

	if err := ns.Start(lb); err != nil {
		return nil, fmt.Errorf("startNetstack: %w", err)
	}
	if b.logger != nil {
		lb.SetLogFlusher(b.logger.StartFlush)
	}
	b.engine = engine
	b.backend = lb
	b.sys = sys
	go func() {
		err := lb.Start(ipn.Options{})
		if err != nil {
			log.Printf("Failed to start LocalBackend, panicking: %s", err)
			panic(err)
		}
		a.ready.Done()
	}()
	return b, nil
}
