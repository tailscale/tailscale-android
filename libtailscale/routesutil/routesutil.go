// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// Package routesutil provides helpers for manipulating the route slices
// produced by wgengine before they are pushed into Android's
// VpnService.Builder. It lives in its own package (rather than inside
// libtailscale) so the pure-Go helpers can be unit-tested without an
// Android NDK toolchain — Makefile's go-test target excludes only the
// libtailscale package itself.
package routesutil

import (
	"cmp"
	"net/netip"
	"slices"

	"tailscale.com/net/tsaddr"
)

// CoalescePeerRoutes folds per-peer /32 (IPv4) and /128 (IPv6) routes
// inside Tailscale's well-known address ranges (the CGNAT range
// 100.64.0.0/10 and the Tailscale ULA fd7a:115c:a1e0::/48) into a single
// parent prefix per family. Other prefixes — subnet routers, exit nodes,
// custom advertised routes — pass through unchanged.
//
// Peer-level routing inside the tailnet is handled by wgengine's
// userspace WireGuard, so the Android VPN layer only needs the parent
// prefix to deliver tailnet-bound traffic into tun0. On a tailnet with
// N peers and subnets-off, the input shrinks from ~N entries to ~2 (one
// /10, one /48) plus any non-tailnet routes.
//
// The output is sorted (IPv4 before IPv6, then ascending prefix length,
// then ascending address) so callers can compare two coalesced sets
// with slices.Equal.
//
// See tailscale/tailscale#19591 for context.
func CoalescePeerRoutes(in []netip.Prefix) []netip.Prefix {
	if len(in) == 0 {
		return nil
	}
	v4 := tsaddr.CGNATRange()
	v6 := tsaddr.TailscaleULARange()

	var hasV4Peer, hasV6Peer bool
	out := make([]netip.Prefix, 0, len(in)+2)
	for _, r := range in {
		switch {
		case r.Bits() == 32 && v4.Contains(r.Addr()):
			hasV4Peer = true
		case r.Bits() == 128 && v6.Contains(r.Addr()):
			hasV6Peer = true
		default:
			out = append(out, r)
		}
	}
	// Avoid emitting a duplicate parent prefix if wgengine already
	// included it among non-peer routes (theoretically possible if the
	// tailnet admin advertises the parent as a subnet route).
	if hasV4Peer && !slices.Contains(out, v4) {
		out = append(out, v4)
	}
	if hasV6Peer && !slices.Contains(out, v6) {
		out = append(out, v6)
	}
	slices.SortFunc(out, prefixLess)
	return out
}

// prefixLess sorts prefixes for stable comparison: IPv4 before IPv6,
// then ascending prefix length, then ascending address.
func prefixLess(a, b netip.Prefix) int {
	if a.Addr().Is4() != b.Addr().Is4() {
		if a.Addr().Is4() {
			return -1
		}
		return 1
	}
	if c := cmp.Compare(a.Bits(), b.Bits()); c != 0 {
		return c
	}
	return a.Addr().Compare(b.Addr())
}
