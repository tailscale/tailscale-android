// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package routesutil

import (
	"net/netip"
	"slices"
	"testing"

	"tailscale.com/net/tsaddr"
)

func mustPfx(s string) netip.Prefix {
	return netip.MustParsePrefix(s)
}

func TestCoalescePeerRoutes(t *testing.T) {
	v4 := tsaddr.CGNATRange()
	v6 := tsaddr.TailscaleULARange()

	tests := []struct {
		name string
		in   []netip.Prefix
		want []netip.Prefix
	}{
		{
			name: "nil",
			in:   nil,
			want: nil,
		},
		{
			name: "empty",
			in:   []netip.Prefix{},
			want: nil,
		},
		{
			name: "no peer routes — passthrough",
			in: []netip.Prefix{
				mustPfx("10.0.0.0/8"),
				mustPfx("192.168.1.0/24"),
				mustPfx("0.0.0.0/0"),
			},
			want: []netip.Prefix{
				mustPfx("0.0.0.0/0"),
				mustPfx("10.0.0.0/8"),
				mustPfx("192.168.1.0/24"),
			},
		},
		{
			name: "single IPv4 peer collapses to CGNAT range",
			in: []netip.Prefix{
				mustPfx("100.64.1.5/32"),
			},
			want: []netip.Prefix{v4},
		},
		{
			name: "many IPv4 peers collapse to single CGNAT range",
			in: []netip.Prefix{
				mustPfx("100.64.1.5/32"),
				mustPfx("100.68.32.10/32"),
				mustPfx("100.70.224.35/32"),
				mustPfx("100.95.255.1/32"),
			},
			want: []netip.Prefix{v4},
		},
		{
			name: "IPv6 peer collapses to ULA range",
			in: []netip.Prefix{
				mustPfx("fd7a:115c:a1e0::a105:e023/128"),
			},
			want: []netip.Prefix{v6},
		},
		{
			name: "mixed v4 + v6 peers",
			in: []netip.Prefix{
				mustPfx("100.64.1.5/32"),
				mustPfx("fd7a:115c:a1e0::a105:e023/128"),
				mustPfx("100.95.255.1/32"),
			},
			want: []netip.Prefix{v4, v6},
		},
		{
			// Output is sorted by (family, bits asc, addr asc): /0, /8, /10, /12 for v4; /0, /48 for v6.
			name: "peer + subnet routers + exit node",
			in: []netip.Prefix{
				mustPfx("10.0.0.0/8"),
				mustPfx("100.64.1.5/32"),
				mustPfx("172.16.0.0/12"),
				mustPfx("100.68.32.10/32"),
				mustPfx("0.0.0.0/0"),
				mustPfx("fd7a:115c:a1e0::a105:e023/128"),
				mustPfx("::/0"),
			},
			want: []netip.Prefix{
				mustPfx("0.0.0.0/0"),
				mustPfx("10.0.0.0/8"),
				v4,                       // 100.64.0.0/10
				mustPfx("172.16.0.0/12"),
				mustPfx("::/0"),
				v6,
			},
		},
		{
			// /10 sorts before /14 (bits ascending) even though both have the same network address.
			name: "non-/32 inside CGNAT range passes through (subnet route, 4via6, etc.)",
			in: []netip.Prefix{
				mustPfx("100.64.0.0/14"),
				mustPfx("100.68.32.10/32"),
			},
			want: []netip.Prefix{
				v4,
				mustPfx("100.64.0.0/14"),
			},
		},
		{
			name: "deduplication when CGNAT range itself is in input",
			in: []netip.Prefix{
				v4,
				mustPfx("100.68.32.10/32"),
				mustPfx("100.95.255.1/32"),
			},
			want: []netip.Prefix{v4}, // not [v4, v4]
		},
		{
			name: "deduplication when ULA range itself is in input",
			in: []netip.Prefix{
				v6,
				mustPfx("fd7a:115c:a1e0::a105:e023/128"),
			},
			want: []netip.Prefix{v6},
		},
		{
			name: "stable sort: same input in different orders → same output",
			in: []netip.Prefix{
				mustPfx("192.168.1.0/24"),
				mustPfx("10.0.0.0/8"),
				mustPfx("172.16.0.0/12"),
			},
			want: []netip.Prefix{
				mustPfx("10.0.0.0/8"),
				mustPfx("172.16.0.0/12"),
				mustPfx("192.168.1.0/24"),
			},
		},
		{
			// /10 sorts before /32 (bits ascending).
			name: "address outside CGNAT range with /32 passes through (e.g. corporate /32)",
			in: []netip.Prefix{
				mustPfx("10.5.5.5/32"),
				mustPfx("100.64.1.5/32"),
			},
			want: []netip.Prefix{
				v4,
				mustPfx("10.5.5.5/32"),
			},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := CoalescePeerRoutes(tt.in)
			if !slices.Equal(got, tt.want) {
				t.Errorf("CoalescePeerRoutes(%v)\n  got:  %v\n  want: %v", tt.in, got, tt.want)
			}
		})
	}
}

// TestCoalescePeerRoutes_StableAcrossPeerChurn is the regression test
// for tailscale/tailscale#19591: adding or removing peers must NOT
// perturb the coalesced output.
func TestCoalescePeerRoutes_StableAcrossPeerChurn(t *testing.T) {
	base := []netip.Prefix{
		mustPfx("10.0.0.0/8"),
		mustPfx("100.64.1.5/32"),
		mustPfx("100.68.32.10/32"),
	}
	withExtraPeer := append(slices.Clone(base), mustPfx("100.95.255.1/32"))
	withFewerPeers := []netip.Prefix{
		mustPfx("10.0.0.0/8"),
		mustPfx("100.64.1.5/32"),
	}

	a := CoalescePeerRoutes(base)
	b := CoalescePeerRoutes(withExtraPeer)
	c := CoalescePeerRoutes(withFewerPeers)

	if !slices.Equal(a, b) {
		t.Errorf("peer-up perturbed output\n  base:    %v\n  +1 peer: %v", a, b)
	}
	if !slices.Equal(a, c) {
		t.Errorf("peer-down perturbed output\n  base:    %v\n  -1 peer: %v", a, c)
	}
}
