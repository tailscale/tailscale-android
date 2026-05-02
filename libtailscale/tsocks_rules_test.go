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

func TestTSocksDecisionOffloadState(t *testing.T) {
	tests := []struct {
		name         string
		target       string
		wantDecision string
		wantReason   string
	}{
		{name: "allowlist_offloaded", target: "104.18.26.120:80", wantDecision: "offloaded", wantReason: "RULE_MATCHED_AND_SOCKS_OFFLOADED"},
		{name: "wrong_port_bypass", target: "104.18.26.120:81", wantDecision: "bypass", wantReason: "RULE_NOT_MATCHED_BUT_ENTERED_TUN_DUE_TO_/32"},
		{name: "recursion_guard_bypass", target: "100.78.63.77:1080", wantDecision: "bypass", wantReason: "RECURSION_GUARD_BYPASS"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			target := netip.MustParseAddrPort(tt.target)
			got := tsocksDecisionOffloadState(matchTSocksRule(target), target)
			if got.Decision != tt.wantDecision || got.Reason != tt.wantReason {
				t.Fatalf("offload = %+v, want decision=%s reason=%s", got, tt.wantDecision, tt.wantReason)
			}
		})
	}
}

func TestTSocksFlowIDCanonicalAcrossDirections(t *testing.T) {
	client := netip.MustParseAddrPort("100.113.1.35:34567")
	server := netip.MustParseAddrPort("104.18.26.120:80")
	forward := tsocksFlowID(client, server)
	reverse := tsocksFlowID(server, client)
	if forward != reverse {
		t.Fatalf("flow IDs differ: forward=%s reverse=%s", forward, reverse)
	}
}
