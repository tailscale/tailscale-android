// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import "testing"

func TestNetworkChangeMailboxKeepsLatestSnapshot(t *testing.T) {
	m := newNetworkChangeMailbox()
	m.send(networkChange{ifname: "wlan0", networkHandle: 1, gateway: "192.0.2.1", dnsConfig: "192.0.2.53"})
	want := networkChange{
		ifname:        "rmnet_data7",
		networkHandle: 2,
		gateway:       "198.51.100.1",
		dnsConfig:     "198.51.100.53\nexample.com",
	}
	m.send(want)
	<-m.wake
	if got := m.take(); got != want {
		t.Fatalf("pending network snapshot = %+v, want %+v", got, want)
	}
}
