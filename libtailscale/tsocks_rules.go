// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"encoding/binary"
	"fmt"
	"hash/fnv"
	"net/netip"
	"sort"
	"strings"
)

type tsocksRule struct {
	Name    string
	Addr    netip.Addr
	Port    uint16
	AnyPort bool
	Route   tsocksRoute
}

var tsocksDatapathRules = []tsocksRule{
	{Name: "socks_server_self", Addr: netip.MustParseAddr(tsocksServerHost), Port: tsocksServerPort, Route: tsocksRouteDirect},
	{Name: "lan_baseline", Addr: netip.MustParseAddr(tsocksLANHost), AnyPort: true, Route: tsocksRouteDirect},
	{Name: "tailnet_lab_baseline", Addr: netip.MustParseAddr(tsocksTailnetLabHost), AnyPort: true, Route: tsocksRouteTailscaleNormal},
	{Name: "public_allowlist_example_com_a_80", Addr: netip.MustParseAddr("104.18.26.120"), Port: 80, Route: tsocksRouteTailnetSocks},
	{Name: "public_allowlist_example_com_b_80", Addr: netip.MustParseAddr("104.18.27.120"), Port: 80, Route: tsocksRouteTailnetSocks},
}

func matchTSocksRule(dst netip.AddrPort) tsocksRouteDecision {
	addr := dst.Addr().Unmap()
	for _, rule := range tsocksDatapathRules {
		if addr != rule.Addr {
			continue
		}
		if !rule.AnyPort && dst.Port() != rule.Port {
			continue
		}
		return tsocksRouteDecision{
			Route:                rule.Route,
			MatchedRule:          rule.Name,
			InjectedRouteApplied: tsocksHasInjectedRoute(addr),
		}
	}
	return tsocksRouteDecision{
		Route:                tsocksRouteDirect,
		MatchedRule:          "default_direct",
		InjectedRouteApplied: tsocksHasInjectedRoute(addr),
	}
}

func tsocksHasInjectedRoute(addr netip.Addr) bool {
	addr = addr.Unmap()
	for _, rule := range tsocksDatapathRules {
		if rule.Route == tsocksRouteTailnetSocks && rule.Addr == addr {
			return true
		}
	}
	return false
}

func tsocksInjectedRouteTargets() []netip.Addr {
	seen := map[netip.Addr]struct{}{}
	var out []netip.Addr
	for _, rule := range tsocksDatapathRules {
		if rule.Route != tsocksRouteTailnetSocks {
			continue
		}
		if _, ok := seen[rule.Addr]; ok {
			continue
		}
		seen[rule.Addr] = struct{}{}
		out = append(out, rule.Addr)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Less(out[j]) })
	return out
}

func tsocksInterceptTargets() []netip.AddrPort {
	var out []netip.AddrPort
	for _, rule := range tsocksDatapathRules {
		if rule.Route != tsocksRouteTailnetSocks || rule.AnyPort {
			continue
		}
		out = append(out, netip.AddrPortFrom(rule.Addr, rule.Port))
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Addr() == out[j].Addr() {
			return out[i].Port() < out[j].Port()
		}
		return out[i].Addr().Less(out[j].Addr())
	})
	return out
}

type tsocksOffloadState struct {
	Decision string
	Reason   string
}

func tsocksCanonicalFlowEndpoints(src, dst netip.AddrPort) (netip.AddrPort, netip.AddrPort) {
	src = netip.AddrPortFrom(src.Addr().Unmap(), src.Port())
	dst = netip.AddrPortFrom(dst.Addr().Unmap(), dst.Port())
	if tsocksHasInjectedRoute(src.Addr()) && !tsocksHasInjectedRoute(dst.Addr()) {
		return dst, src
	}
	return src, dst
}

func tsocksFlowID(src, dst netip.AddrPort) string {
	client, server := tsocksCanonicalFlowEndpoints(src, dst)
	h := fnv.New64a()
	_, _ = h.Write([]byte(client.Addr().String()))
	var ports [4]byte
	binary.BigEndian.PutUint16(ports[0:2], client.Port())
	binary.BigEndian.PutUint16(ports[2:4], server.Port())
	_, _ = h.Write(ports[:])
	_, _ = h.Write([]byte(server.Addr().String()))
	_, _ = h.Write([]byte("tcp"))
	return fmt.Sprintf("%016x", h.Sum64())
}

func tsocksDecisionOffloadState(decision tsocksRouteDecision, dst netip.AddrPort) tsocksOffloadState {
	if tsocksDecisionRecursionGuard(decision) {
		return tsocksOffloadState{Decision: "bypass", Reason: "RECURSION_GUARD_BYPASS"}
	}
	if decision.Route == tsocksRouteTailnetSocks && tsocksShouldOffloadTarget(dst) {
		return tsocksOffloadState{Decision: "offloaded", Reason: "RULE_MATCHED_AND_SOCKS_OFFLOADED"}
	}
	if decision.InjectedRouteApplied {
		return tsocksOffloadState{Decision: "bypass", Reason: "RULE_NOT_MATCHED_BUT_ENTERED_TUN_DUE_TO_/32"}
	}
	return tsocksOffloadState{Decision: "bypass", Reason: "BASELINE_NATIVE_PATH_OK"}
}

func tsocksDecisionRecursionGuard(decision tsocksRouteDecision) bool {
	return decision.MatchedRule == "socks_server_self"
}

func tsocksShouldOffloadTarget(dst netip.AddrPort) bool {
	for _, target := range tsocksInterceptTargets() {
		if target == dst {
			return true
		}
	}
	return false
}

func tsocksTargetsSummary(targets []netip.AddrPort) string {
	parts := make([]string, 0, len(targets))
	for _, target := range targets {
		parts = append(parts, target.String())
	}
	return strings.Join(parts, ",")
}
