// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"errors"
	"fmt"
	"log"
	"net/netip"
	"runtime/debug"
	"strings"
	"syscall"

	"github.com/tailscale/tailscale-android/libtailscale/ifaceparse"
	"github.com/tailscale/wireguard-go/tun"
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
	jsonStr, err := a.appCtx.GetInterfacesAsJson()
	if err != nil {
		return nil, err
	}
	jsonStr = strings.TrimSpace(jsonStr)
	if jsonStr == "" {
		return nil, nil
	}

	ifaces, st, err := ifaceparse.ParseInterfacesJSONAsNetmon([]byte(jsonStr))
	if err != nil {
		return nil, err
	}

	if st.IfacesSkipped > 0 || st.AddrsSkipped > 0 {
		log.Printf("getInterfaces(JSON): parsed %d/%d ifaces, %d/%d addrs (skipped %d ifaces, %d addrs)",
			st.IfacesParsed, st.IfacesTotal, st.AddrsParsed, st.AddrsTotal, st.IfacesSkipped, st.AddrsSkipped)

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

func (b *backend) updateTUN(rcfg *router.Config, dcfg *dns.OSConfig) error {
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

	// Calculate effective allowed routes (Routes minus LocalRoutes) and add them to the builder.
	prefixesV4, prefixesV6 := newRangesCalc(rcfg.Routes, rcfg.LocalRoutes).calculate()

	for _, route := range prefixesV4 {
		// Normalize route address; Builder.addRoute does not accept non-zero masked bits.
		route = route.Masked()
		if err := builder.AddRoute(route.Addr().String(), int32(route.Bits())); err != nil {
			return err
		}
	}
	for _, route := range prefixesV6 {
		// Normalize route address; Builder.addRoute does not accept non-zero masked bits.
		route = route.Masked()
		if err := builder.AddRoute(route.Addr().String(), int32(route.Bits())); err != nil {
			return err
		}
	}

	b.logger.Logf(
		"updateTUN: added routes: v4=%d v6=%d total=%d (input routes=%d, localRoutes=%d)",
		len(prefixesV4),
		len(prefixesV6),
		len(prefixesV4)+len(prefixesV6),
		len(rcfg.Routes),
		len(rcfg.LocalRoutes),
	)
	b.logger.Logf("updateTUN: input routes: %v", rcfg.Routes)
	b.logger.Logf("updateTUN: input local routes: %v", rcfg.LocalRoutes)
	b.logger.Logf("updateTUN: effective routes v4: %v", prefixesV4)
	b.logger.Logf("updateTUN: effective routes v6: %v", prefixesV6)

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
		b.logger.Logf("updateTUN: could not establish VPN because builder.Establish returned a nil ParcelFileDescriptor")
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
	if b.netMon != nil {
		b.netMon.InjectEvent()
	} else {
		log.Printf("NetworkChanged: netMon is nil")
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
		if len(ret.Nameservers) == 0 && b.appCtx.ShouldUseGoogleDNSFallback() {
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
