// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"net/netip"
	"testing"
)

func TestMatchTSocksRule(t *testing.T) {
	tests := []struct {
		name         string
		target       string
		wantRoute    tsocksRoute
		wantRule     string
		wantInjected bool
	}{
		{name: "public_a_exact", target: "104.18.26.120:80", wantRoute: tsocksRouteTailnetSocks, wantRule: "public_allowlist_example_com_a_80", wantInjected: true},
		{name: "public_b_exact", target: "104.18.27.120:80", wantRoute: tsocksRouteTailnetSocks, wantRule: "public_allowlist_example_com_b_80", wantInjected: true},
		{name: "public_a_wrong_port", target: "104.18.26.120:81", wantRoute: tsocksRouteDirect, wantRule: "default_direct", wantInjected: true},
		{name: "lan_wildcard", target: "192.168.31.101:19080", wantRoute: tsocksRouteDirect, wantRule: "lan_baseline", wantInjected: false},
		{name: "tailnet_lab_wildcard", target: "100.109.193.113:443", wantRoute: tsocksRouteTailscaleNormal, wantRule: "tailnet_lab_baseline", wantInjected: false},
		{name: "socks_self_exact", target: "100.78.63.77:1080", wantRoute: tsocksRouteDirect, wantRule: "socks_server_self", wantInjected: false},
		{name: "public_no_match", target: "104.18.4.106:80", wantRoute: tsocksRouteDirect, wantRule: "default_direct", wantInjected: false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			target := netip.MustParseAddrPort(tt.target)
			got := matchTSocksRule(target)
			if got.Route != tt.wantRoute {
				t.Fatalf("route = %s, want %s", got.Route, tt.wantRoute)
			}
			if got.MatchedRule != tt.wantRule {
				t.Fatalf("matchedRule = %s, want %s", got.MatchedRule, tt.wantRule)
			}
			if got.InjectedRouteApplied != tt.wantInjected {
				t.Fatalf("injectedRoute = %t, want %t", got.InjectedRouteApplied, tt.wantInjected)
			}
		})
	}
}

func TestTSocksInjectedRouteTargets(t *testing.T) {
	want := []netip.Addr{
		netip.MustParseAddr("104.18.26.120"),
		netip.MustParseAddr("104.18.27.120"),
	}
	got := tsocksInjectedRouteTargets()
	if len(got) != len(want) {
		t.Fatalf("len(routes) = %d, want %d", len(got), len(want))
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("routes[%d] = %s, want %s", i, got[i], want[i])
		}
	}
}
