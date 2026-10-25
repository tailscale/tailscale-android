// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package ifaceparse

import (
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"slices"
	"strings"

	"tailscale.com/net/netmon"
)

type ParseStats struct {
	IfacesTotal   int
	IfacesParsed  int
	IfacesSkipped int
	AddrsTotal    int
	AddrsParsed   int
	AddrsSkipped  int
}

type addrJSON struct {
	IP        string `json:"ip"`
	PrefixLen int    `json:"prefixLen"`
}
type ifaceJSON struct {
	Name      string     `json:"name"`
	Index     int        `json:"index"`
	MTU       int        `json:"mtu"`
	Up        bool       `json:"up"`
	Broadcast bool       `json:"broadcast"`
	Loopback  bool       `json:"loopback"`
	PointToPt bool       `json:"pointToPoint"`
	Multicast bool       `json:"multicast"`
	Addrs     []addrJSON `json:"addrs"`
}

var ErrNotJSON = errors.New("not a JSON interfaces payload")

// ParseInterfacesJSONAsNetmon parses a JSON payload produced by getInterfacesAsJson()
// and returns netmon.Interfaces plus parsing stats.
func ParseInterfacesJSONAsNetmon(b []byte) ([]netmon.Interface, ParseStats, error) {
	var st ParseStats
	trim := strings.TrimSpace(string(b))
	if trim == "" {
		return nil, st, nil
	}
	if !(strings.HasPrefix(trim, "[") || strings.HasPrefix(trim, "{")) {
		return nil, st, ErrNotJSON
	}

	var in []ifaceJSON
	if err := json.Unmarshal([]byte(trim), &in); err != nil {
		return nil, st, err
	}

	out := make([]netmon.Interface, 0, len(in))
	for _, it := range in {
		st.IfacesTotal++

		if it.Name == "" {
			st.IfacesSkipped++
			continue
		}

		nif := netmon.Interface{
			Interface: &net.Interface{
				Name:  it.Name,
				Index: it.Index,
				MTU:   it.MTU,
			},
			AltAddrs: []net.Addr{},
		}

		if it.Up {
			nif.Flags |= net.FlagUp
		}
		if it.Broadcast {
			nif.Flags |= net.FlagBroadcast
		}
		if it.Loopback {
			nif.Flags |= net.FlagLoopback
		}
		if it.PointToPt {
			nif.Flags |= net.FlagPointToPoint
		}
		if it.Multicast {
			nif.Flags |= net.FlagMulticast
		}

		st.AddrsTotal += len(it.Addrs)
		for _, a := range it.Addrs {
			na, err := a.NetAddr()
			if err != nil {
				st.AddrsSkipped++
				continue
			}
			nif.AltAddrs = append(nif.AltAddrs, na)
			st.AddrsParsed++
		}

		out = append(out, nif)
		st.IfacesParsed++
	}

	return out, st, nil
}

func (a addrJSON) NetAddr() (net.Addr, error) {
	na, err := netip.ParseAddr(a.IP)
	if err != nil {
		return nil, err
	}

	zone := na.Zone()
	ip := net.IP(slices.Clone(na.AsSlice()))

	// Zoned addresses can't be represented as *net.IPNet.
	if zone != "" {
		return &net.IPAddr{IP: ip, Zone: zone}, nil
	}

	bits := 128
	if na.Is4() {
		bits = 32
	}
	if a.PrefixLen < 0 || a.PrefixLen > bits {
		// Keep the IP but drop the prefix if it's invalid.
		return &net.IPAddr{IP: ip}, nil
	}

	// Host IP + prefixLen mask (not prefix base).
	return &net.IPNet{
		IP:   ip,
		Mask: net.CIDRMask(a.PrefixLen, bits),
	}, nil
}
