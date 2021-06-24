// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package main

import (
	"errors"
	"fmt"
	"log"
	"path/filepath"
	"reflect"
	"time"

	"github.com/tailscale/tailscale-android/jni"
	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/tun"
	"inet.af/netaddr"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnlocal"
	"tailscale.com/logtail"
	"tailscale.com/logtail/filch"
	"tailscale.com/net/dns"
	"tailscale.com/types/logger"
	"tailscale.com/wgengine"
	"tailscale.com/wgengine/router"
)

type backend struct {
	engine     wgengine.Engine
	backend    *ipnlocal.LocalBackend
	devices    *multiTUN
	settings   settingsFunc
	lastCfg    *router.Config
	lastDNSCfg *dns.OSConfig

	// avoidEmptyDNS controls whether to use fallback nameservers
	// when no nameservers are provided by Tailscale.
	avoidEmptyDNS bool

	jvm *jni.JVM
}

type settingsFunc func(*router.Config, *dns.OSConfig) error

const defaultMTU = 1280 // minimalMTU from wgengine/userspace.go

const (
	logPrefKey         = "privatelogid"
	loginMethodPrefKey = "loginmethod"
)

const (
	loginMethodGoogle = "google"
	loginMethodWeb    = "web"
)

var fallbackNameservers = []netaddr.IP{netaddr.IPv4(8, 8, 8, 8), netaddr.IPv4(8, 8, 4, 4)}

// errVPNNotPrepared is used when VPNService.Builder.establish returns
// null, either because the VPNService is not yet prepared or because
// VPN status was revoked.
var errVPNNotPrepared = errors.New("VPN service not prepared or was revoked")

func newBackend(dataDir string, jvm *jni.JVM, store *stateStore, settings settingsFunc) (*backend, error) {
	logf := logger.RusagePrefixLog(log.Printf)
	b := &backend{
		jvm:      jvm,
		devices:  newTUNDevices(),
		settings: settings,
	}
	var logID logtail.PrivateID
	logID.UnmarshalText([]byte("dead0000dead0000dead0000dead0000dead0000dead0000dead0000dead0000"))
	storedLogID, err := store.read(logPrefKey)
	// In all failure cases we ignore any errors and continue with the dead value above.
	if err != nil || storedLogID == nil {
		// Read failed or there was no previous log id.
		newLogID, err := logtail.NewPrivateID()
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
	b.SetupLogs(dataDir, logID)
	cb := &router.CallbackRouter{
		SetBoth:  b.setCfg,
		SplitDNS: false, // TODO: https://github.com/tailscale/tailscale/issues/1695
	}
	engine, err := wgengine.NewUserspaceEngine(logf, wgengine.Config{
		Tun:    b.devices,
		Router: cb,
		DNS:    cb,
	})
	if err != nil {
		return nil, fmt.Errorf("runBackend: NewUserspaceEngineAdvanced: %v", err)
	}
	local, err := ipnlocal.NewLocalBackend(logf, logID.Public().String(), store, engine)
	if err != nil {
		engine.Close()
		return nil, fmt.Errorf("runBackend: NewLocalBackend: %v", err)
	}
	b.engine = engine
	b.backend = local
	return b, nil
}

func (b *backend) Start(notify func(n ipn.Notify)) error {
	b.backend.SetNotifyCallback(notify)
	return b.backend.Start(ipn.Options{
		StateKey: "ipn-android",
	})
}

func (b *backend) LinkChange() {
	if b.engine != nil {
		b.engine.LinkChange(false)
	}
}

func (b *backend) setCfg(rcfg *router.Config, dcfg *dns.OSConfig) error {
	return b.settings(rcfg, dcfg)
}

func (b *backend) updateTUN(service jni.Object, rcfg *router.Config, dcfg *dns.OSConfig) error {
	if reflect.DeepEqual(rcfg, b.lastCfg) && reflect.DeepEqual(dcfg, b.lastDNSCfg) {
		return nil
	}

	// Close previous tunnel(s).
	// This is necessary for ChromeOS, native Android devices
	// seem to handle seamless handover between tunnels correctly.
	//
	// TODO(eliasnaur): If seamless handover becomes a desirable feature, skip
	// the closing on ChromeOS.
	b.CloseTUNs()

	if len(rcfg.LocalAddrs) == 0 {
		return nil
	}
	err := jni.Do(b.jvm, func(env *jni.Env) error {
		cls := jni.GetObjectClass(env, service)
		// Construct a VPNService.Builder. IPNService.newBuilder calls
		// setConfigureIntent, and allowFamily for both IPv4 and IPv6.
		m := jni.GetMethodID(env, cls, "newBuilder", "()Landroid/net/VpnService$Builder;")
		builder, err := jni.CallObjectMethod(env, service, m)
		if err != nil {
			return fmt.Errorf("IPNService.newBuilder: %v", err)
		}
		bcls := jni.GetObjectClass(env, builder)

		// builder.setMtu.
		setMtu := jni.GetMethodID(env, bcls, "setMtu", "(I)Landroid/net/VpnService$Builder;")
		const mtu = defaultMTU
		if _, err := jni.CallObjectMethod(env, builder, setMtu, jni.Value(mtu)); err != nil {
			return fmt.Errorf("VpnService.Builder.setMtu: %v", err)
		}

		// builder.addDnsServer
		addDnsServer := jni.GetMethodID(env, bcls, "addDnsServer", "(Ljava/lang/String;)Landroid/net/VpnService$Builder;")
		// builder.addSearchDomain.
		addSearchDomain := jni.GetMethodID(env, bcls, "addSearchDomain", "(Ljava/lang/String;)Landroid/net/VpnService$Builder;")
		if dcfg != nil {
			nameservers := dcfg.Nameservers
			if b.avoidEmptyDNS && len(nameservers) == 0 {
				nameservers = fallbackNameservers
			}
			for _, dns := range nameservers {
				_, err = jni.CallObjectMethod(env,
					builder,
					addDnsServer,
					jni.Value(jni.JavaString(env, dns.String())),
				)
				if err != nil {
					return fmt.Errorf("VpnService.Builder.addDnsServer(%v): %v", dns, err)
				}
			}

			for _, dom := range dcfg.SearchDomains {
				_, err = jni.CallObjectMethod(env,
					builder,
					addSearchDomain,
					jni.Value(jni.JavaString(env, dom.WithoutTrailingDot())),
				)
				if err != nil {
					return fmt.Errorf("VpnService.Builder.addSearchDomain(%v): %v", dom, err)
				}
			}
		}

		// builder.addRoute.
		addRoute := jni.GetMethodID(env, bcls, "addRoute", "(Ljava/lang/String;I)Landroid/net/VpnService$Builder;")
		for _, route := range rcfg.Routes {
			// Normalize route address; Builder.addRoute does not accept non-zero masked bits.
			route = route.Masked()
			_, err = jni.CallObjectMethod(env,
				builder,
				addRoute,
				jni.Value(jni.JavaString(env, route.IP().String())),
				jni.Value(route.Bits()),
			)
			if err != nil {
				return fmt.Errorf("VpnService.Builder.addRoute(%v): %v", route, err)
			}
		}

		// builder.addAddress.
		addAddress := jni.GetMethodID(env, bcls, "addAddress", "(Ljava/lang/String;I)Landroid/net/VpnService$Builder;")
		for _, addr := range rcfg.LocalAddrs {
			_, err = jni.CallObjectMethod(env,
				builder,
				addAddress,
				jni.Value(jni.JavaString(env, addr.IP().String())),
				jni.Value(addr.Bits()),
			)
			if err != nil {
				return fmt.Errorf("VpnService.Builder.addAddress(%v): %v", addr, err)
			}
		}

		// builder.establish.
		establish := jni.GetMethodID(env, bcls, "establish", "()Landroid/os/ParcelFileDescriptor;")
		parcelFD, err := jni.CallObjectMethod(env, builder, establish)
		if err != nil {
			return fmt.Errorf("VpnService.Builder.establish: %v", err)
		}
		if parcelFD == 0 {
			return errVPNNotPrepared
		}

		// detachFd.
		parcelCls := jni.GetObjectClass(env, parcelFD)
		detachFd := jni.GetMethodID(env, parcelCls, "detachFd", "()I")
		tunFD, err := jni.CallIntMethod(env, parcelFD, detachFd)
		if err != nil {
			return fmt.Errorf("detachFd: %v", err)
		}

		// Create TUN device.
		tunDev, _, err := tun.CreateUnmonitoredTUNFromFD(int(tunFD))
		if err != nil {
			unix.Close(int(tunFD))
			return err
		}

		b.devices.add(tunDev)

		return nil
	})
	if err != nil {
		b.lastCfg = nil
		b.CloseTUNs()
		return err
	}
	b.lastCfg = rcfg
	b.lastDNSCfg = dcfg
	return nil
}

// CloseVPN closes any active TUN devices.
func (b *backend) CloseTUNs() {
	b.lastCfg = nil
	b.devices.Shutdown()
}

// SetupLogs sets up remote logging.
func (b *backend) SetupLogs(logDir string, logID logtail.PrivateID) {
	logcfg := logtail.Config{
		Collection: "tailnode.log.tailscale.io",
		PrivateID:  logID,
		Stderr:     log.Writer(),
	}
	drainCh := make(chan struct{})
	logcfg.DrainLogs = drainCh
	go func() {
		// Upload logs infrequently. Interval chosen arbitrarily.
		// The objective is to reduce phone power use.
		t := time.NewTicker(2 * time.Minute)
		for range t.C {
			select {
			case drainCh <- struct{}{}:
			default:
			}
		}
	}()

	filchOpts := filch.Options{
		ReplaceStderr: true,
	}

	var filchErr error
	if logDir != "" {
		logPath := filepath.Join(logDir, "ipn.log.")
		logcfg.Buffer, filchErr = filch.New(logPath, filchOpts)
	}

	logf := logger.RusagePrefixLog(log.Printf)
	tlog := logtail.NewLogger(logcfg, logf)

	log.SetFlags(0)
	log.SetOutput(tlog)

	log.Printf("goSetupLogs: success")

	if logDir == "" {
		log.Printf("SetupLogs: no logDir, storing logs in memory")
	}
	if filchErr != nil {
		log.Printf("SetupLogs: filch setup failed: %v", filchErr)
	}
}
