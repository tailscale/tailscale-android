// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package main

import (
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/netip"
	"path/filepath"
	"reflect"
	"strings"
	"time"

	"github.com/tailscale/tailscale-android/jni"
	"github.com/tailscale/wireguard-go/tun"
	"golang.org/x/sys/unix"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnlocal"
	"tailscale.com/logpolicy"
	"tailscale.com/logtail"
	"tailscale.com/logtail/filch"
	"tailscale.com/net/dns"
	"tailscale.com/net/netmon"
	"tailscale.com/net/tsdial"
	"tailscale.com/smallzstd"
	"tailscale.com/tsd"
	"tailscale.com/types/logger"
	"tailscale.com/types/logid"
	"tailscale.com/util/clientmetric"
	"tailscale.com/util/dnsname"
	"tailscale.com/util/must"
	"tailscale.com/wgengine"
	"tailscale.com/wgengine/netstack"
	"tailscale.com/wgengine/router"
)

type backend struct {
	engine     wgengine.Engine
	backend    *ipnlocal.LocalBackend
	sys        *tsd.System
	devices    *multiTUN
	settings   settingsFunc
	lastCfg    *router.Config
	lastDNSCfg *dns.OSConfig

	logIDPublic string
	logger      *logtail.Logger

	// avoidEmptyDNS controls whether to use fallback nameservers
	// when no nameservers are provided by Tailscale.
	avoidEmptyDNS bool

	jvm    *jni.JVM
	appCtx jni.Object
}

type settingsFunc func(*router.Config, *dns.OSConfig) error

const defaultMTU = 1280 // minimalMTU from wgengine/userspace.go

const (
	logPrefKey               = "privatelogid"
	loginMethodPrefKey       = "loginmethod"
	customLoginServerPrefKey = "customloginserver"
)

const (
	loginMethodGoogle = "google"
	loginMethodWeb    = "web"
)

// googleDNSServers are used on ChromeOS, where an empty VpnBuilder DNS setting results
// in erasing the platform DNS servers. The developer docs say this is not supposed to happen,
// but nonetheless it does.
var googleDNSServers = []netip.Addr{
	netip.MustParseAddr("8.8.8.8"),
	netip.MustParseAddr("8.8.4.4"),
	netip.MustParseAddr("2001:4860:4860::8888"),
	netip.MustParseAddr("2001:4860:4860::8844"),
}

// errVPNNotPrepared is used when VPNService.Builder.establish returns
// null, either because the VPNService is not yet prepared or because
// VPN status was revoked.
var errVPNNotPrepared = errors.New("VPN service not prepared or was revoked")

// errMultipleUsers is used when we get a "INTERACT_ACROSS_USERS" error, which
// happens due to a bug in Android. See:
//
//	https://github.com/tailscale/tailscale/issues/2180
var errMultipleUsers = errors.New("VPN cannot be created on this device due to an Android bug with multiple users")

func newBackend(dataDir string, jvm *jni.JVM, appCtx jni.Object, store *stateStore,
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
	b.SetupLogs(dataDir, logID)
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
	})
	if err != nil {
		return nil, fmt.Errorf("runBackend: NewUserspaceEngine: %v", err)
	}
	sys.Set(engine)
	b.logIDPublic = logID.Public().String()
	ns, err := netstack.Create(logf, sys.Tun.Get(), engine, sys.MagicSock.Get(), dialer, sys.DNSManager.Get())
	if err != nil {
		return nil, fmt.Errorf("netstack.Create: %w", err)
	}
	sys.Set(ns)
	ns.ProcessLocalIPs = false // let Android kernel handle it; VpnBuilder sets this up
	ns.ProcessSubnets = true   // for Android-being-an-exit-node support
	sys.NetstackRouter.Set(true)
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

func (b *backend) Start(notify func(n ipn.Notify)) error {
	b.backend.SetNotifyCallback(notify)
	return b.backend.Start(ipn.Options{})
}

func (b *backend) LinkChange() {
	if b.sys != nil {
		if nm, ok := b.sys.NetMon.GetOK(); ok {
			nm.InjectEvent()
		}
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
				nameservers = googleDNSServers
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
				jni.Value(jni.JavaString(env, route.Addr().String())),
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
				jni.Value(jni.JavaString(env, addr.Addr().String())),
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
			if strings.Contains(err.Error(), "INTERACT_ACROSS_USERS") {
				return errMultipleUsers
			}
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
func (b *backend) SetupLogs(logDir string, logID logid.PrivateID) {
	logf := logger.RusagePrefixLog(log.Printf)
	netMon, err := netmon.New(func(format string, args ...any) {
		logf(format, args...)
	})
	if err != nil {
		log.Printf("netmon.New: %w", err)
	}
	transport := logpolicy.NewLogtailTransport(logtail.DefaultHost, netMon, log.Printf)

	logcfg := logtail.Config{
		Collection:          logtail.CollectionNode,
		PrivateID:           logID,
		Stderr:              log.Writer(),
		MetricsDelta:        clientmetric.EncodeLogTailMetricsDelta,
		IncludeProcID:       true,
		IncludeProcSequence: true,
		NewZstdEncoder: func() logtail.Encoder {
			return must.Get(smallzstd.NewEncoder(nil))
		},
		HTTPC: &http.Client{Transport: transport},
	}
	logcfg.FlushDelayFn = func() time.Duration { return 2 * time.Minute }

	filchOpts := filch.Options{
		ReplaceStderr: true,
	}

	var filchErr error
	if logDir != "" {
		logPath := filepath.Join(logDir, "ipn.log.")
		logcfg.Buffer, filchErr = filch.New(logPath, filchOpts)
	}

	b.logger = logtail.NewLogger(logcfg, logf)

	log.SetFlags(0)
	log.SetOutput(b.logger)

	log.Printf("goSetupLogs: success")

	if logDir == "" {
		log.Printf("SetupLogs: no logDir, storing logs in memory")
	}
	if filchErr != nil {
		log.Printf("SetupLogs: filch setup failed: %v", filchErr)
	}
}

// We log the result of each of the DNS configuration discovery mechanisms, as we're
// expecting a long tail of obscure Android devices with interesting behavior.
func (b *backend) logDNSConfigMechanisms() {
	err := jni.Do(b.jvm, func(env *jni.Env) error {
		cls := jni.GetObjectClass(env, b.appCtx)
		m := jni.GetMethodID(env, cls, "getDnsConfigObj", "()Lcom/tailscale/ipn/DnsConfig;")
		dns, err := jni.CallObjectMethod(env, b.appCtx, m)
		if err != nil {
			return fmt.Errorf("getDnsConfigObj JNI: %v", err)
		}
		dnsCls := jni.GetObjectClass(env, dns)

		for _, impl := range []string{"getDnsConfigFromLinkProperties",
			"getDnsServersFromSystemProperties",
			"getDnsServersFromNetworkInfo"} {

			m = jni.GetMethodID(env, dnsCls, impl, "()Ljava/lang/String;")
			n, err := jni.CallObjectMethod(env, dns, m)
			baseConfig := jni.GoString(env, jni.String(n))
			if err != nil {
				log.Printf("%s JNI: %v", impl, err)
			} else {
				oneLine := strings.Replace(baseConfig, "\n", ";", -1)
				log.Printf("%s: %s", impl, oneLine)
			}
		}
		return nil
	})
	if err != nil {
		log.Printf("logDNSConfigMechanisms: %v", err)
	}
}

func (b *backend) getPlatformDNSConfig() string {
	var baseConfig string
	err := jni.Do(b.jvm, func(env *jni.Env) error {
		cls := jni.GetObjectClass(env, b.appCtx)
		m := jni.GetMethodID(env, cls, "getDnsConfigObj", "()Lcom/tailscale/ipn/DnsConfig;")
		dns, err := jni.CallObjectMethod(env, b.appCtx, m)
		if err != nil {
			return fmt.Errorf("getDnsConfigObj: %v", err)
		}
		dnsCls := jni.GetObjectClass(env, dns)
		m = jni.GetMethodID(env, dnsCls, "getDnsConfigAsString", "()Ljava/lang/String;")
		n, err := jni.CallObjectMethod(env, dns, m)
		baseConfig = jni.GoString(env, jni.String(n))
		return err
	})
	if err != nil {
		log.Printf("getPlatformDNSConfig JNI: %v", err)
		return ""
	}
	return baseConfig
}

func (b *backend) getDNSBaseConfig() (ret dns.OSConfig, _ error) {
	defer func() {
		// If we couldn't find any base nameservers, ultimately fall back to
		// Google's. Normally Tailscale doesn't ever pick a default nameserver
		// for users but in this case Android's APIs for reading the underlying
		// DNS config are lacking, and almost all Android phones use Google
		// services anyway, so it's a reasonable default: it's an ecosystem the
		// user has selected by having an Android device.
		if len(ret.Nameservers) == 0 && googleSignInEnabled() {
			log.Printf("getDNSBaseConfig: none found; falling back to Google public DNS")
			ret.Nameservers = append(ret.Nameservers, googleDNSServers...)
		}
	}()
	b.logDNSConfigMechanisms()
	baseConfig := b.getPlatformDNSConfig()
	lines := strings.Split(baseConfig, "\n")
	if len(lines) == 0 {
		return dns.OSConfig{}, nil
	}

	config := dns.OSConfig{}
	addrs := strings.Trim(lines[0], " \n")
	for _, addr := range strings.Split(addrs, " ") {
		ip, err := netip.ParseAddr(addr)
		if err == nil {
			config.Nameservers = append(config.Nameservers, ip)
		}
	}

	if len(lines) > 1 {
		for _, s := range strings.Split(strings.Trim(lines[1], " \n"), " ") {
			domain, err := dnsname.ToFQDN(s)
			if err != nil {
				log.Printf("getDNSBaseConfig: unable to parse %q: %v", s, err)
				continue
			}
			config.SearchDomains = append(config.SearchDomains, domain)
		}
	}

	return config, nil
}
