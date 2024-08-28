// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"errors"
	"fmt"
	"log"
	"net"
	"net/netip"
	"reflect"
	"runtime/debug"
	"strings"
	"syscall"

	"github.com/tailscale/wireguard-go/tun"
	"inet.af/netaddr"
	"tailscale.com/net/dns"
	"tailscale.com/net/netmon"
	"tailscale.com/util/dnsname"
	"tailscale.com/wgengine/router"
)

// errVPNNotPrepared is used when VPNService.Builder.establish returns
// null, either because the VPNService is not yet prepared or because
// VPN status was revoked.
var errVPNNotPrepared = errors.New("VPN service not prepared or was revoked")

// errMultipleUsers is used when we get a "INTERACT_ACROSS_USERS" error, which
// happens due to a bug in Android. See:
//
//	https://github.com/tailscale/tailscale/issues/2180
var errMultipleUsers = errors.New("VPN cannot be created on this device due to an Android bug with multiple users")

// VpnService contains the IPNService class from Android, the file descriptor, and whether the descriptor has been detached.
type VpnService struct {
	service    IPNService
	fd         int32
	fdDetached bool
}

var vpnService = &VpnService{}

// Report interfaces in the device in net.Interface format.
func (a *App) getInterfaces() ([]netmon.Interface, error) {
	var ifaces []netmon.Interface

	ifaceString, err := a.appCtx.GetInterfacesAsString()
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

		newIf := netmon.Interface{
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

func (b *backend) updateTUN(service IPNService, rcfg *router.Config, dcfg *dns.OSConfig) error {
	if reflect.DeepEqual(rcfg, b.lastCfg) && reflect.DeepEqual(dcfg, b.lastDNSCfg) {
		b.logger.Logf("updateTUN: no change to Routes or DNS, ignore")
		return nil
	}

	b.logger.Logf("updateTUN: changed")
	defer b.logger.Logf("updateTUN: finished")

	// Close previous tunnel(s).
	// This is necessary for ChromeOS, native Android devices
	// seem to handle seamless handover between tunnels correctly.
	//
	// TODO(eliasnaur): If seamless handover becomes a desirable feature, skip
	// the closing on ChromeOS.
	b.logger.Logf("updateTUN: closing old TUNs")
	b.CloseTUNs()
	b.logger.Logf("updateTUN: closed old TUNs")

	if len(rcfg.LocalAddrs) == 0 {
		return nil
	}
	vpnService.service = service
	builder := vpnService.service.NewBuilder()
	b.logger.Logf("updateTUN: got new builder")

	if err := builder.SetMTU(defaultMTU); err != nil {
		return err
	}
	b.logger.Logf("updateTUN: set MTU")
	if dcfg != nil {
		nameservers := dcfg.Nameservers
		if b.avoidEmptyDNS && len(nameservers) == 0 {
			nameservers = googleDNSServers
		}
		for _, dns := range nameservers {
			if err := builder.AddDNSServer(dns.String()); err != nil {
				return err
			}
		}
		for _, dom := range dcfg.SearchDomains {
			if err := builder.AddSearchDomain(dom.WithoutTrailingDot()); err != nil {
				return err
			}
		}
		b.logger.Logf("updateTUN: set nameservers")
	}

	for _, route := range rcfg.Routes {
		// Normalize route address; Builder.addRoute does not accept non-zero masked bits.
		route = route.Masked()
		if err := builder.AddRoute(route.Addr().String(), int32(route.Bits())); err != nil {
			return err
		}
	}

	for _, route := range rcfg.LocalRoutes {
		addr := route.Addr()
		if addr.IsLoopback() {
			continue // Skip the loopback addresses since VpnService throws an exception for those (both IPv4 and IPv6) - see https://android.googlesource.com/platform/frameworks/base/+/c741553/core/java/android/net/VpnService.java#303
		}
		route = route.Masked()
		if err := builder.ExcludeRoute(route.Addr().String(), int32(route.Bits())); err != nil {
			return err
		}
	}
	b.logger.Logf("updateTUN: added %d routes", len(rcfg.Routes))

	for _, addr := range rcfg.LocalAddrs {
		if err := builder.AddAddress(addr.Addr().String(), int32(addr.Bits())); err != nil {
			return err
		}
	}
	b.logger.Logf("updateTUN: added %d local addrs", len(rcfg.LocalAddrs))

	parcelFD, err := builder.Establish()
	if err != nil {
		if strings.Contains(err.Error(), "INTERACT_ACROSS_USERS") {
			// Update VPN status if VPN interface cannot be created
			b.logger.Logf("updateTUN: could not establish VPN because %v", err)
			vpnService.service.UpdateVpnStatus(false)
			return errMultipleUsers
		}
		return fmt.Errorf("VpnService.Builder.establish: %v", err)
	}
	log.Printf("Setting vpn activity status to true")
	vpnService.service.UpdateVpnStatus(true)
	b.logger.Logf("updateTUN: established VPN")

	if parcelFD == nil {
		return errVPNNotPrepared
	}

	// detachFd.
	tunFD, err := parcelFD.Detach()
	vpnService.fdDetached = true
	vpnService.fd = tunFD

	if err != nil {
		return fmt.Errorf("detachFd: %v", err)
	}
	b.logger.Logf("updateTUN: detached FD")

	// Create TUN device.
	tunDev, _, err := tun.CreateUnmonitoredTUNFromFD(int(tunFD))
	if err != nil {
		closeFileDescriptor()
		return err
	}
	b.logger.Logf("updateTUN: created TUN device")

	b.devices.add(tunDev)
	b.logger.Logf("updateTUN: added TUN device")

	b.lastCfg = rcfg
	b.lastDNSCfg = dcfg
	return nil
}

func closeFileDescriptor() error {
	if vpnService.fd != -1 && vpnService.fdDetached {
		err := syscall.Close(int(vpnService.fd))
		vpnService.fd = -1
		vpnService.fdDetached = false
		return fmt.Errorf("error closing file descriptor: %w", err)
	}
	return nil
}

// CloseVPN closes any active TUN devices.
func (b *backend) CloseTUNs() {
	b.lastCfg = nil
	b.devices.Shutdown()
	vpnService.service = nil
}

// ifname is the interface name retrieved from LinkProperties on network change. If a network is lost, an empty string is passed in.
func (b *backend) NetworkChanged(ifname string) {
	defer func() {
		if p := recover(); p != nil {
			log.Printf("panic in NetworkChanged %s: %s", p, debug.Stack())
			panic(p)
		}
	}()

	// Set the interface name and alert the monitor.
	netmon.UpdateLastKnownDefaultRouteInterface(ifname)
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
		if len(ret.Nameservers) == 0 && b.appCtx.IsPlayVersion() {
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
	return b.appCtx.GetPlatformDNSConfig()
}

func (b *backend) setCfg(rcfg *router.Config, dcfg *dns.OSConfig) error {
	return b.settings(rcfg, dcfg)
}
