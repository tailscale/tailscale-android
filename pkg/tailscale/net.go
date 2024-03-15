// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package main

import (
	"errors"
	"fmt"
	"log"
	"net"
	"net/netip"
	"reflect"
	"strings"

	jnipkg "github.com/tailscale/tailscale-android/pkg/jni"
	"github.com/tailscale/wireguard-go/tun"
	"golang.org/x/sys/unix"
	"inet.af/netaddr"
	"tailscale.com/net/dns"
	"tailscale.com/net/interfaces"
	"tailscale.com/util/dnsname"
	"tailscale.com/wgengine/router"
)

import "C"

// errVPNNotPrepared is used when VPNService.Builder.establish returns
// null, either because the VPNService is not yet prepared or because
// VPN status was revoked.
var errVPNNotPrepared = errors.New("VPN service not prepared or was revoked")

// errMultipleUsers is used when we get a "INTERACT_ACROSS_USERS" error, which
// happens due to a bug in Android. See:
//
//	https://github.com/tailscale/tailscale/issues/2180
var errMultipleUsers = errors.New("VPN cannot be created on this device due to an Android bug with multiple users")

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

func (b *backend) setCfg(rcfg *router.Config, dcfg *dns.OSConfig) error {
	return b.settings(rcfg, dcfg)
}
