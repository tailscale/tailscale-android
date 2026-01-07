// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package ifaceparse

import (
	"net"
	"sort"
	"testing"

	"tailscale.com/net/netmon"
)

func TestParseInterfacesJSONAsNetmon(t *testing.T) {
	tests := []struct {
		name string
		json string

		wantIfaceName string
		wantPresent   []string
		wantAbsent    []string

		wantIfacesTotal   *int
		wantIfacesParsed  *int
		wantIfacesSkipped *int
		wantAddrsTotal    *int
		minAddrsParsed    *int
	}{
		{
			name: "basic_v4_v6_and_zoned_linklocal",
			json: `[
				{
					"name":"wlan0",
					"index":30,
					"mtu":1500,
					"up":true,
					"broadcast":true,
					"loopback":false,
					"pointToPoint":false,
					"multicast":true,
					"addrs":[
						{"ip":"fe80::2f60:2c82:4163:8389%wlan0","prefixLen":64},
						{"ip":"10.1.10.131","prefixLen":24},
						{"ip":"2601:647:6801:2640:842b:a104:7efe:3f74","prefixLen":64}
					]
				}
			]`,
			wantIfaceName: "wlan0",
			wantPresent: []string{
				"10.1.10.131",
				"10.1.10.131/24",
				"2601:647:6801:2640:842b:a104:7efe:3f74",
				"2601:647:6801:2640:842b:a104:7efe:3f74/64",
				"fe80::2f60:2c82:4163:8389%wlan0",
			},
			wantAbsent: []string{
				"10.1.10.0",
				"2601:647:6801:2640::",
				"2601:647:6801:2640::/64",
			},
			wantIfacesTotal:   intp(1),
			wantIfacesParsed:  intp(1),
			wantIfacesSkipped: intp(0),
			wantAddrsTotal:    intp(3),
			minAddrsParsed:    intp(2),
		},
		{
			name: "empty_addrs_ok",
			json: `[
				{
					"name":"wlan0",
					"index":30,
					"mtu":1500,
					"up":true,
					"broadcast":true,
					"loopback":false,
					"pointToPoint":false,
					"multicast":true,
					"addrs":[]
				}
			]`,
			wantIfaceName:     "wlan0",
			wantPresent:       nil,
			wantAbsent:        []string{"10.1.10.0"},
			wantIfacesTotal:   intp(1),
			wantIfacesParsed:  intp(1),
			wantAddrsTotal:    intp(0),
			minAddrsParsed:    intp(0),
			wantIfacesSkipped: intp(0),
		},
		{
			name: "skips_bad_ip_but_keeps_good",
			json: `[
				{
					"name":"wlan0",
					"index":30,
					"mtu":1500,
					"up":true,
					"broadcast":true,
					"loopback":false,
					"pointToPoint":false,
					"multicast":true,
					"addrs":[
						{"ip":"not-an-ip","prefixLen":24},
						{"ip":"10.1.10.131","prefixLen":24}
					]
				}
			]`,
			wantIfaceName:    "wlan0",
			wantPresent:      []string{"10.1.10.131/24"},
			wantAbsent:       []string{"10.1.10.0"},
			wantIfacesTotal:  intp(1),
			wantIfacesParsed: intp(1),
			wantAddrsTotal:   intp(2),
			minAddrsParsed:   intp(1),
		},
		{
			name: "skips_iface_with_empty_name",
			json: `[
				{"name":"","index":1,"mtu":1500,"up":true,"broadcast":false,"loopback":false,"pointToPoint":false,"multicast":false,"addrs":[{"ip":"10.0.0.1","prefixLen":24}]},
				{"name":"wlan0","index":30,"mtu":1500,"up":true,"broadcast":true,"loopback":false,"pointToPoint":false,"multicast":true,"addrs":[{"ip":"10.1.10.131","prefixLen":24}]}
			]`,
			wantIfaceName:     "wlan0",
			wantPresent:       []string{"10.1.10.131/24"},
			wantIfacesTotal:   intp(2),
			wantIfacesParsed:  intp(1),
			wantIfacesSkipped: intp(1),
			wantAddrsTotal:    intp(1), // only counts addrs for parsed iface
			minAddrsParsed:    intp(1),
		},
		{
			name: "non_json_rejected",
			json: "not json",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			ifaces, st, err := ParseInterfacesJSONAsNetmon([]byte(tc.json))

			if tc.name == "non_json_rejected" {
				if err == nil {
					t.Fatalf("expected error, got nil (stats=%+v ifaces=%v)", st, ifaces)
				}
				return
			}

			if err != nil {
				t.Fatalf("parse error: %v", err)
			}

			if len(ifaces) != 1 {
				t.Fatalf("expected 1 parsed interface, got %d (stats=%+v)", len(ifaces), st)
			}
			if ifaces[0].Name != tc.wantIfaceName {
				t.Fatalf("expected interface %q, got %q", tc.wantIfaceName, ifaces[0].Name)
			}

			if tc.wantIfacesTotal != nil && st.IfacesTotal != *tc.wantIfacesTotal {
				t.Fatalf("IfacesTotal=%d, want %d (stats=%+v)", st.IfacesTotal, *tc.wantIfacesTotal, st)
			}
			if tc.wantIfacesParsed != nil && st.IfacesParsed != *tc.wantIfacesParsed {
				t.Fatalf("IfacesParsed=%d, want %d (stats=%+v)", st.IfacesParsed, *tc.wantIfacesParsed, st)
			}
			if tc.wantIfacesSkipped != nil && st.IfacesSkipped != *tc.wantIfacesSkipped {
				t.Fatalf("IfacesSkipped=%d, want %d (stats=%+v)", st.IfacesSkipped, *tc.wantIfacesSkipped, st)
			}
			if tc.wantAddrsTotal != nil && st.AddrsTotal != *tc.wantAddrsTotal {
				t.Fatalf("AddrsTotal=%d, want %d (stats=%+v)", st.AddrsTotal, *tc.wantAddrsTotal, st)
			}
			if tc.minAddrsParsed != nil && st.AddrsParsed < *tc.minAddrsParsed {
				t.Fatalf("AddrsParsed=%d, want >= %d (stats=%+v)", st.AddrsParsed, *tc.minAddrsParsed, st)
			}

			got := collectAltAddrStrings(t, ifaces[0])

			for _, want := range tc.wantPresent {
				if !got[want] {
					t.Fatalf("missing %q; got=%v", want, keys(got))
				}
			}
			for _, bad := range tc.wantAbsent {
				if got[bad] {
					t.Fatalf("unexpected %q; got=%v", bad, keys(got))
				}
			}
		})
	}
}

// collectAltAddrStrings formats AltAddrs into comparable strings.
// Supports both *net.IPNet and *net.IPAddr.
func collectAltAddrStrings(t *testing.T, ifc netmon.Interface) map[string]bool {
	t.Helper()

	out := map[string]bool{}
	for _, a := range ifc.AltAddrs {
		switch v := a.(type) {
		case *net.IPNet:
			out[v.String()] = true // includes /prefix
			out[v.IP.String()] = true
		case *net.IPAddr:
			out[v.IP.String()] = true
			if v.Zone != "" {
				out[v.IP.String()+"%"+v.Zone] = true
			}
		default:
			t.Fatalf("unexpected AltAddrs type: %T", a)
		}
	}
	return out
}

func keys(m map[string]bool) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

func intp(v int) *int { return &v }
