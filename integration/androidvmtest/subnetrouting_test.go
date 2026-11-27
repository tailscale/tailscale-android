// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package androidvmtest

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/ipn/store/mem"
	"tailscale.com/net/tsaddr"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
	"tailscale.com/types/views"
)

const (
	subnetPeerHostname = "tsnetsubnetpeer"
	viaSiteID          = 7
)

var (
	viaSite = netip.MustParsePrefix("198.51.100.0/24")
	viaHost = netip.MustParseAddr("198.51.100.7")
)

func fourViaSix(t *testing.T, e *androidEnv, sp *tsnet.Server) {
	addr, want := e.advertiseVia(t, sp)
	openSubnetRouting(t, e)
	useSubnets := appString(t, strUseTailscaleSubnets)
	pollUI(t, e.adb, "the Use Tailscale subnets switch on", func(s uiScreen) bool {
		if s.pkg() != androidPackage {
			dismissForeignApp(t, e, s.pkg())
			return false
		}
		return s.has(withText(useSubnets)) && s.has(isCheckedSwitch)
	})
	e.waitForProbeBody(t, "http://["+addr.String()+"]/", want)
}

func runningAsSubnetRouter(t *testing.T, e *androidEnv, sp *tsnet.Server) {
	lanURL, _ := startLANServer(t)
	route := netip.PrefixFrom(netip.MustParseAddr(*emulatorHost), 24).Masked()
	openSubnetRouting(t, e)
	tap(t, e.adb, waitForAppNode(t, e, "the Add route row", withText(appString(t, strAddNewRoute))))
	typeInto(t, e.adb, waitForAppNode(t, e, "the route field", withClass(editTextClass)), route.String())
	hideKeyboard(t, e.adb)
	tap(t, e.adb, waitForAppNode(t, e, "the OK button", withText(appString(t, strOK))))
	row := withText(route.String())
	waitForSubnetRoutes(t, e, route.String()+" in Advertised routes", func(s uiScreen) bool { return s.has(row) })

	phone := e.waitForPhoneHostinfo(t, fmt.Sprintf("the phone advertising %v", route), func(hi tailcfg.HostinfoView) bool {
		return views.SliceContains(hi.RoutableIPs(), route)
	})
	e.control.SetSubnetRoutes(phone, []netip.Prefix{route})
	editPrefs(t, sp, &ipn.MaskedPrefs{Prefs: ipn.Prefs{RouteAll: true}, RouteAllSet: true})
	waitForPeerBody(t, sp, lanURL, lanBody)

	tap(t, e.adb, waitForAppNode(t, e, "the Delete route button", withDesc(appString(t, strDeleteRoute))))
	waitForSubnetRoutes(t, e, route.String()+" gone from Advertised routes", func(s uiScreen) bool { return !s.has(row) })
	e.waitForPhoneHostinfo(t, fmt.Sprintf("the phone no longer advertising %v", route), func(hi tailcfg.HostinfoView) bool {
		return !views.SliceContains(hi.RoutableIPs(), route)
	})
	e.control.SetSubnetRoutes(phone, nil)
}

// startSubnetPeer adds a peer apart from e.peer, whose exit node handler resets other flows.
func (e *androidEnv) startSubnetPeer(t *testing.T) *tsnet.Server {
	t.Helper()
	s := &tsnet.Server{
		Dir:        t.TempDir(),
		ControlURL: e.peer.ControlURL,
		AuthKey:    authKey,
		Hostname:   subnetPeerHostname,
		Ephemeral:  true,
		Logf:       logger.WithPrefix(t.Logf, "subnet tsnet: "),
		Store:      new(mem.Store),
	}
	t.Cleanup(func() { s.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if _, err := s.Up(ctx); err != nil {
		t.Fatalf("subnet tsnet peer Up: %v", err)
	}
	return s
}

func (e *androidEnv) advertiseVia(t *testing.T, sp *tsnet.Server) (addr netip.Addr, want string) {
	t.Helper()
	route, err := tsaddr.MapVia(viaSiteID, viaSite)
	if err != nil {
		t.Fatal(err)
	}
	host, err := tsaddr.MapVia(viaSiteID, netip.PrefixFrom(viaHost, viaHost.BitLen()))
	if err != nil {
		t.Fatal(err)
	}
	reply := func(target netip.AddrPort) string { return fmt.Sprintf("4via6 router got %v", target) }
	sp.RegisterFallbackTCPHandler(func(src, dst netip.AddrPort) (func(net.Conn), bool) {
		if !route.Contains(dst.Addr()) {
			return nil, false
		}
		target := netip.AddrPortFrom(tsaddr.UnmapVia(dst.Addr()), dst.Port())
		return func(c net.Conn) { replyHTTP(c, reply(target)) }, true
	})
	editPrefs(t, sp, &ipn.MaskedPrefs{Prefs: ipn.Prefs{AdvertiseRoutes: []netip.Prefix{route}}, AdvertiseRoutesSet: true})

	lc, err := sp.LocalClient()
	if err != nil {
		t.Fatalf("subnet tsnet peer LocalClient: %v", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		t.Fatalf("subnet tsnet peer Status: %v", err)
	}
	e.control.SetSubnetRoutes(st.Self.PublicKey, []netip.Prefix{route})
	return host.Addr(), reply(netip.AddrPortFrom(viaHost, 80))
}

func openSubnetRouting(t *testing.T, e *androidEnv) {
	t.Helper()
	openDeepLink(t, e, settingsDeepLink)
	tap(t, e.adb, waitForAppNode(t, e, "the Subnet routing row", withText(appString(t, strSubnetRouting))))
	add := appString(t, strAddNewRoute)
	waitForAppNode(t, e, add, withText(add))
}

func waitForSubnetRoutes(t *testing.T, e *androidEnv, what string, match func(uiScreen) bool) {
	t.Helper()
	add, failed := appString(t, strAddNewRoute), appString(t, strFailedToSave)
	pollUI(t, e.adb, what, func(s uiScreen) bool {
		if s.pkg() != androidPackage {
			dismissForeignApp(t, e, s.pkg())
			return false
		}
		if s.has(withText(failed)) {
			t.Fatalf("the app failed to save the routes while waiting for %s", what)
		}
		return s.has(withText(add)) && match(s)
	})
}

func (e *androidEnv) waitForPhoneHostinfo(t *testing.T, what string, match func(tailcfg.HostinfoView) bool) key.NodePublic {
	t.Helper()
	for deadline := time.Now().Add(*waitTimeout); time.Now().Before(deadline); time.Sleep(500 * time.Millisecond) {
		for _, n := range e.control.AllNodes() {
			if n.Hostinfo.OS() == "android" && match(n.Hostinfo) {
				return n.Key
			}
		}
	}
	t.Fatalf("testcontrol never saw %s", what)
	return key.NodePublic{}
}

func editPrefs(t *testing.T, s *tsnet.Server, mp *ipn.MaskedPrefs) {
	t.Helper()
	lc, err := s.LocalClient()
	if err != nil {
		t.Fatalf("%s LocalClient: %v", s.Hostname, err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if _, err := lc.EditPrefs(ctx, mp); err != nil {
		t.Fatalf("%s EditPrefs: %v", s.Hostname, err)
	}
}

func waitForPeerBody(t *testing.T, s *tsnet.Server, rawURL, want string) {
	t.Helper()
	c := s.HTTPClient()
	c.Timeout = 10 * time.Second
	var last string
	for deadline := time.Now().Add(*waitTimeout); time.Now().Before(deadline); time.Sleep(time.Second) {
		res, err := c.Get(rawURL)
		if err != nil {
			last = err.Error()
			continue
		}
		body, err := io.ReadAll(res.Body)
		res.Body.Close()
		if err == nil && string(body) == want {
			return
		}
		last = fmt.Sprintf("status %d, body %q, read error %v", res.StatusCode, body, err)
	}
	t.Fatalf("%s never got %q from %s; last result: %s", s.Hostname, want, rawURL, last)
}
