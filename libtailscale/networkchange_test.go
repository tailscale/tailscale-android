// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"testing"

	"tailscale.com/net/netmon"
)

func TestAppliedNetworkDNSConfig(t *testing.T) {
	b := new(backend)
	t.Cleanup(func() {
		netmon.UpdateLastKnownDefaultRouteInterface("")
	})
	first := networkChange{
		ifname:        "wlan0",
		networkHandle: 1,
		gateway:       "192.0.2.1",
		dnsConfig:     "192.0.2.53",
	}
	b.applyAndroidNetworkChange(first)
	if got := b.getPlatformDNSConfig(); got != first.dnsConfig {
		t.Fatalf("applied DNS config = %q, want %q", got, first.dnsConfig)
	}

	b.applyAndroidNetworkChange(networkChange{}) // Offline retains the last DNS config.
	if got := b.getPlatformDNSConfig(); got != first.dnsConfig {
		t.Fatalf("offline DNS config = %q, want %q", got, first.dnsConfig)
	}

	second := networkChange{
		ifname:        "rmnet_data7",
		networkHandle: 2,
		gateway:       "198.51.100.1",
		dnsConfig:     "198.51.100.53",
	}
	b.applyAndroidNetworkChange(second)
	if got := b.getPlatformDNSConfig(); got != second.dnsConfig {
		t.Fatalf("replacement DNS config = %q, want %q", got, second.dnsConfig)
	}
}
