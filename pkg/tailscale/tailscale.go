// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/netip"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	jnipkg "github.com/tailscale/tailscale-android/pkg/jni"
	"github.com/tailscale/tailscale-android/pkg/localapiservice"
	"github.com/tailscale/wireguard-go/tun"
	"golang.org/x/sys/unix"
	"inet.af/netaddr"
	"tailscale.com/hostinfo"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnlocal"
	"tailscale.com/ipn/localapi"
	"tailscale.com/logpolicy"
	"tailscale.com/logtail"
	"tailscale.com/logtail/filch"
	"tailscale.com/net/dns"
	"tailscale.com/net/interfaces"
	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
	"tailscale.com/net/tsdial"
	"tailscale.com/paths"
	"tailscale.com/smallzstd"
	"tailscale.com/tsd"
	"tailscale.com/types/logger"
	"tailscale.com/types/logid"
	"tailscale.com/types/netmap"
	"tailscale.com/util/clientmetric"
	"tailscale.com/util/dnsname"
	"tailscale.com/util/must"
	"tailscale.com/wgengine"
	"tailscale.com/wgengine/netstack"
	"tailscale.com/wgengine/router"
)

import "C"

var dataDirChan = make(chan string, 1)

var (
	dataDirOnce sync.Once
	dataPath    string
)

func dataDir() (string, error) {
	dataDirOnce.Do(func() {
		dataPath = <-dataDirChan
	})
	return dataPath, nil
}

var (
	// googleClass is a global reference to the com.tailscale.ipn.Google class.
	googleClass jnipkg.Class
)

type App struct {
	jvm *jnipkg.JVM
	// appCtx is a global reference to the com.tailscale.ipn.App instance.
	appCtx jnipkg.Object

	store             *stateStore
	logIDPublicAtomic atomic.Pointer[logid.PublicID]

	localAPI *localapiservice.LocalAPIService
	backend  *ipnlocal.LocalBackend

	// netStates receives the most recent network state.
	netStates chan BackendState
	// invalidates receives whenever the window should be refreshed.
	invalidates chan struct{}
}

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

const defaultMTU = 1280 // minimalMTU from wgengine/userspace.go

const (
	logPrefKey               = "privatelogid"
	loginMethodPrefKey       = "loginmethod"
	customLoginServerPrefKey = "customloginserver"
)

type ConnectEvent struct {
	Enable bool
}

func main() {
	a := &App{
		jvm:         (*jnipkg.JVM)(unsafe.Pointer(javaVM())),
		appCtx:      jnipkg.Object(appContext()),
		netStates:   make(chan BackendState, 1),
		invalidates: make(chan struct{}, 1),
	}

	err := a.loadJNIGlobalClassRefs()
	if err != nil {
		fatalErr(err)
	}

	a.store = newStateStore(a.jvm, a.appCtx)
	interfaces.RegisterInterfaceGetter(a.getInterfaces)
	go func() {
		ctx := context.Background()
		if err := a.runBackend(ctx); err != nil {
			fatalErr(err)
		}
	}()
}

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

// errVPNNotPrepared is used when VPNService.Builder.establish returns
// null, either because the VPNService is not yet prepared or because
// VPN status was revoked.
var errVPNNotPrepared = errors.New("VPN service not prepared or was revoked")

// errMultipleUsers is used when we get a "INTERACT_ACROSS_USERS" error, which
// happens due to a bug in Android. See:
//
//	https://github.com/tailscale/tailscale/issues/2180
var errMultipleUsers = errors.New("VPN cannot be created on this device due to an Android bug with multiple users")

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

func fatalErr(err error) {
	// TODO: expose in UI.
	log.Printf("fatal error: %v", err)
}

func javaVM() uintptr {
	android.mu.Lock()
	defer android.mu.Unlock()
	return uintptr(unsafe.Pointer(android.jvm))
}

func appContext() uintptr {
	android.mu.Lock()
	defer android.mu.Unlock()
	return uintptr(android.appCtx)
}

// Report interfaces in the device in net.Interface format.
func (a *App) getInterfaces() ([]interfaces.Interface, error) {
	var ifaceString string
	err := jnipkg.Do(a.jvm, func(env *jnipkg.Env) error {
		cls := jnipkg.GetObjectClass(env, a.appCtx)
		m := jnipkg.GetMethodID(env, cls, "getInterfacesAsString", "()Ljava/lang/String;")
		n, err := jnipkg.CallObjectMethod(env, a.appCtx, m)
		ifaceString = jnipkg.GoString(env, jnipkg.String(n))
		return err

	})
	var ifaces []interfaces.Interface
	if err != nil {
		return ifaces, err
	}

	for _, iface := range strings.Split(ifaceString, "\n") {
		// Example of the strings we're processing:
		// wlan0 30 1500 true true false false true | fe80::2f60:2c82:4163:8389%wlan0/64 10.1.10.131/24
		// r_rmnet_data0 21 1500 true false false false false | fe80::9318:6093:d1ad:ba7f%r_rmnet_data0/64
		// mnet_data2 12 1500 true false false false false | fe80::3c8c:44dc:46a9:9907%rmnet_data2/64

		if strings.TrimSpace(iface) == "" {
			continue
		}

		fields := strings.Split(iface, "|")
		if len(fields) != 2 {
			log.Printf("getInterfaces: unable to split %q", iface)
			continue
		}

		var name string
		var index, mtu int
		var up, broadcast, loopback, pointToPoint, multicast bool
		_, err := fmt.Sscanf(fields[0], "%s %d %d %t %t %t %t %t",
			&name, &index, &mtu, &up, &broadcast, &loopback, &pointToPoint, &multicast)
		if err != nil {
			log.Printf("getInterfaces: unable to parse %q: %v", iface, err)
			continue
		}

		newIf := interfaces.Interface{
			Interface: &net.Interface{
				Name:  name,
				Index: index,
				MTU:   mtu,
			},
			AltAddrs: []net.Addr{}, // non-nil to avoid Go using netlink
		}
		if up {
			newIf.Flags |= net.FlagUp
		}
		if broadcast {
			newIf.Flags |= net.FlagBroadcast
		}
		if loopback {
			newIf.Flags |= net.FlagLoopback
		}
		if pointToPoint {
			newIf.Flags |= net.FlagPointToPoint
		}
		if multicast {
			newIf.Flags |= net.FlagMulticast
		}

		addrs := strings.Trim(fields[1], " \n")
		for _, addr := range strings.Split(addrs, " ") {
			ip, err := netaddr.ParseIPPrefix(addr)
			if err == nil {
				newIf.AltAddrs = append(newIf.AltAddrs, ip.IPNet())
			}
		}

		ifaces = append(ifaces, newIf)
	}

	return ifaces, nil
}

// osVersion returns android.os.Build.VERSION.RELEASE. " [nogoogle]" is appended
// if Google Play services are not compiled in.
func (a *App) osVersion() string {
	var version string
	err := jnipkg.Do(a.jvm, func(env *jnipkg.Env) error {
		cls := jnipkg.GetObjectClass(env, a.appCtx)
		m := jnipkg.GetMethodID(env, cls, "getOSVersion", "()Ljava/lang/String;")
		n, err := jnipkg.CallObjectMethod(env, a.appCtx, m)
		version = jnipkg.GoString(env, jnipkg.String(n))
		return err
	})
	if err != nil {
		panic(err)
	}
	return version
}

// modelName return the MANUFACTURER + MODEL from
// android.os.Build.
func (a *App) modelName() string {
	var model string
	err := jnipkg.Do(a.jvm, func(env *jnipkg.Env) error {
		cls := jnipkg.GetObjectClass(env, a.appCtx)
		m := jnipkg.GetMethodID(env, cls, "getModelName", "()Ljava/lang/String;")
		n, err := jnipkg.CallObjectMethod(env, a.appCtx, m)
		model = jnipkg.GoString(env, jnipkg.String(n))
		return err
	})
	if err != nil {
		panic(err)
	}
	return model
}

func (a *App) isChromeOS() bool {
	var chromeOS bool
	err := jnipkg.Do(a.jvm, func(env *jnipkg.Env) error {
		cls := jnipkg.GetObjectClass(env, a.appCtx)
		m := jnipkg.GetMethodID(env, cls, "isChromeOS", "()Z")
		b, err := jnipkg.CallBooleanMethod(env, a.appCtx, m)
		chromeOS = b
		return err
	})
	if err != nil {
		panic(err)
	}
	return chromeOS
}

func googleSignInEnabled() bool {
	return googleClass != 0
}

// Loads the global JNI class references.  Failures here are fatal if the
// class ref is required for the app to function.
func (a *App) loadJNIGlobalClassRefs() error {
	return jnipkg.Do(a.jvm, func(env *jnipkg.Env) error {
		loader := jnipkg.ClassLoaderFor(env, a.appCtx)
		cl, err := jnipkg.LoadClass(env, loader, "com.tailscale.ipn.Google")
		if err != nil {
			// Ignore load errors; the Google class is not included in F-Droid builds.
			return nil
		}
		googleClass = jnipkg.Class(jnipkg.NewGlobalRef(env, jnipkg.Object(cl)))
		return nil
	})
}

// googleDNSServers are used on ChromeOS, where an empty VpnBuilder DNS setting results
// in erasing the platform DNS servers. The developer docs say this is not supposed to happen,
// but nonetheless it does.
var googleDNSServers = []netip.Addr{
	netip.MustParseAddr("8.8.8.8"),
	netip.MustParseAddr("8.8.4.4"),
	netip.MustParseAddr("2001:4860:4860::8888"),
	netip.MustParseAddr("2001:4860:4860::8844"),
}

func (b *backend) updateTUN(service jnipkg.Object, rcfg *router.Config, dcfg *dns.OSConfig) error {
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
	err := jnipkg.Do(b.jvm, func(env *jnipkg.Env) error {
		cls := jnipkg.GetObjectClass(env, service)
		// Construct a VPNService.Builder. IPNService.newBuilder calls
		// setConfigureIntent, and allowFamily for both IPv4 and IPv6.
		m := jnipkg.GetMethodID(env, cls, "newBuilder", "()Landroid/net/VpnService$Builder;")
		builder, err := jnipkg.CallObjectMethod(env, service, m)
		if err != nil {
			return fmt.Errorf("IPNService.newBuilder: %v", err)
		}
		bcls := jnipkg.GetObjectClass(env, builder)

		// builder.setMtu.
		setMtu := jnipkg.GetMethodID(env, bcls, "setMtu", "(I)Landroid/net/VpnService$Builder;")
		const mtu = defaultMTU
		if _, err := jnipkg.CallObjectMethod(env, builder, setMtu, jnipkg.Value(mtu)); err != nil {
			return fmt.Errorf("VpnService.Builder.setMtu: %v", err)
		}

		// builder.addDnsServer
		addDnsServer := jnipkg.GetMethodID(env, bcls, "addDnsServer", "(Ljava/lang/String;)Landroid/net/VpnService$Builder;")
		// builder.addSearchDomain.
		addSearchDomain := jnipkg.GetMethodID(env, bcls, "addSearchDomain", "(Ljava/lang/String;)Landroid/net/VpnService$Builder;")
		if dcfg != nil {
			nameservers := dcfg.Nameservers
			if b.avoidEmptyDNS && len(nameservers) == 0 {
				nameservers = googleDNSServers
			}
			for _, dns := range nameservers {
				_, err = jnipkg.CallObjectMethod(env,
					builder,
					addDnsServer,
					jnipkg.Value(jnipkg.JavaString(env, dns.String())),
				)
				if err != nil {
					return fmt.Errorf("VpnService.Builder.addDnsServer(%v): %v", dns, err)
				}
			}

			for _, dom := range dcfg.SearchDomains {
				_, err = jnipkg.CallObjectMethod(env,
					builder,
					addSearchDomain,
					jnipkg.Value(jnipkg.JavaString(env, dom.WithoutTrailingDot())),
				)
				if err != nil {
					return fmt.Errorf("VpnService.Builder.addSearchDomain(%v): %v", dom, err)
				}
			}
		}

		// builder.addRoute.
		addRoute := jnipkg.GetMethodID(env, bcls, "addRoute", "(Ljava/lang/String;I)Landroid/net/VpnService$Builder;")
		for _, route := range rcfg.Routes {
			// Normalize route address; Builder.addRoute does not accept non-zero masked bits.
			route = route.Masked()
			_, err = jnipkg.CallObjectMethod(env,
				builder,
				addRoute,
				jnipkg.Value(jnipkg.JavaString(env, route.Addr().String())),
				jnipkg.Value(route.Bits()),
			)
			if err != nil {
				return fmt.Errorf("VpnService.Builder.addRoute(%v): %v", route, err)
			}
		}

		// builder.addAddress.
		addAddress := jnipkg.GetMethodID(env, bcls, "addAddress", "(Ljava/lang/String;I)Landroid/net/VpnService$Builder;")
		for _, addr := range rcfg.LocalAddrs {
			_, err = jnipkg.CallObjectMethod(env,
				builder,
				addAddress,
				jnipkg.Value(jnipkg.JavaString(env, addr.Addr().String())),
				jnipkg.Value(addr.Bits()),
			)
			if err != nil {
				return fmt.Errorf("VpnService.Builder.addAddress(%v): %v", addr, err)
			}
		}

		// builder.establish.
		establish := jnipkg.GetMethodID(env, bcls, "establish", "()Landroid/os/ParcelFileDescriptor;")
		parcelFD, err := jnipkg.CallObjectMethod(env, builder, establish)
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
		parcelCls := jnipkg.GetObjectClass(env, parcelFD)
		detachFd := jnipkg.GetMethodID(env, parcelCls, "detachFd", "()I")
		tunFD, err := jnipkg.CallIntMethod(env, parcelFD, detachFd)
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

func (b *backend) NetworkChanged() {
	if b.sys != nil {
		if nm, ok := b.sys.NetMon.GetOK(); ok {
			nm.InjectEvent()
		}
	}
}

func (b *backend) setCfg(rcfg *router.Config, dcfg *dns.OSConfig) error {
	return b.settings(rcfg, dcfg)
}

// SetupLogs sets up remote logging.
func (b *backend) setupLogs(logDir string, logID logid.PrivateID, logf logger.Logf) {
	if b.netMon == nil {
		panic("netMon must be created prior to SetupLogs")
	}
	transport := logpolicy.NewLogtailTransport(logtail.DefaultHost, b.netMon, log.Printf)

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

func (b *backend) getPlatformDNSConfig() string {
	var baseConfig string
	err := jnipkg.Do(b.jvm, func(env *jnipkg.Env) error {
		cls := jnipkg.GetObjectClass(env, b.appCtx)
		m := jnipkg.GetMethodID(env, cls, "getDnsConfigObj", "()Lcom/tailscale/ipn/DnsConfig;")
		dns, err := jnipkg.CallObjectMethod(env, b.appCtx, m)
		if err != nil {
			return fmt.Errorf("getDnsConfigObj: %v", err)
		}
		dnsCls := jnipkg.GetObjectClass(env, dns)
		m = jnipkg.GetMethodID(env, dnsCls, "getDnsConfigAsString", "()Ljava/lang/String;")
		n, err := jnipkg.CallObjectMethod(env, dns, m)
		baseConfig = jnipkg.GoString(env, jnipkg.String(n))
		return err
	})
	if err != nil {
		log.Printf("getPlatformDNSConfig JNI: %v", err)
		return ""
	}
	return baseConfig
}
