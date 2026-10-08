// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"net"
	"net/netip"

	"tailscale.com/net/tsdial"
	"tailscale.com/wgengine"
)

// configureNetstackDialer routes backend-originated connections to peers through
// netstack instead of Android sockets that can loop back through the VPN or bind
// to the physical network. Both UDP DNS and its TCP fallback need this wiring.
// See tailscale/tailscale#20983.
// Call before starting the backend; peerForIP must consult the current routes.
func configureNetstackDialer[T, U net.Conn](d *tsdial.Dialer,
	peerForIP func(netip.Addr) (wgengine.PeerForIP, bool),
	tcp func(context.Context, netip.AddrPort) (T, error),
	udp func(context.Context, netip.AddrPort) (U, error),
) {
	d.UseNetstackForIP = func(ip netip.Addr) bool {
		_, ok := peerForIP(ip)
		return ok
	}
	d.NetstackDialTCP = netstackDialFunc(tcp)
	d.NetstackDialUDP = netstackDialFunc(udp)
}

// netstackDialFunc adapts netstack's concrete connection types without returning
// a typed nil inside a net.Conn interface on failure.
func netstackDialFunc[T net.Conn](dial func(context.Context, netip.AddrPort) (T, error)) func(context.Context, netip.AddrPort) (net.Conn, error) {
	return func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
		conn, err := dial(ctx, dst)
		if err != nil {
			return nil, err
		}
		return conn, nil
	}
}
