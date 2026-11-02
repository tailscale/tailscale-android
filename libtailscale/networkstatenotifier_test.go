// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import "testing"

func TestNetworkStateNotifierKeepsLatestSnapshot(t *testing.T) {
	m := newNetworkStateNotifier()
	m.send(networkState{ifname: "wlan0", networkHandle: 1, gateway: "192.0.2.1", dnsConfig: "192.0.2.53"})
	want := networkState{
		ifname:        "rmnet_data7",
		networkHandle: 2,
		gateway:       "198.51.100.1",
		dnsConfig:     "198.51.100.53\nexample.com",
	}
	m.send(want)
	<-m.pending
	if got := m.peek(); got != want {
		t.Fatalf("pending network snapshot = %+v, want %+v", got, want)
	}
}
