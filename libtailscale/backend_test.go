// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"net/netip"
	"reflect"
	"testing"

	"go4.org/netipx"
	"tailscale.com/net/dns"
	"tailscale.com/wgengine/router"
)

func TestIsConfigNonNilAndDifferent_InterfaceChange(t *testing.T) {
	// Create a minimal backend for testing.
	b := &backend{
		lastCfg:    &router.Config{},
		lastDNSCfg: &dns.OSConfig{},
	}

	// Same config, same interface -> should return false
	rcfg := &router.Config{}
	dcfg := &dns.OSConfig{}
	b.appliedUnderlyingIface = "rmnet_data0"
	b.lastUnderlyingIface = "rmnet_data0"
	if b.isConfigNonNilAndDifferent(rcfg, dcfg) {
		t.Fatal("expected false when config and interface unchanged")
	}

	// Same config, different interface -> should return true
	b.lastUnderlyingIface = "wlan0"
	if !b.isConfigNonNilAndDifferent(rcfg, dcfg) {
		t.Fatal("expected true when interface changed from rmnet_data0 to wlan0")
	}

	// After updateTUN would be called, appliedUnderlyingIface is updated
	b.appliedUnderlyingIface = "wlan0"
	if b.isConfigNonNilAndDifferent(rcfg, dcfg) {
		t.Fatal("expected false after applied interface updated to wlan0")
	}

	// Config changes should still trigger update
	b.lastUnderlyingIface = "wlan0"
	b.appliedUnderlyingIface = "wlan0"
	newRCfg := &router.Config{Routes: []netipx.IPPrefix{{}}}
	if !b.isConfigNonNilAndDifferent(newRCfg, dcfg) {
		t.Fatal("expected true when router.Config changed")
	}

	// DNS config changes should still trigger update
	b.lastCfg = newRCfg
	newDCfg := &dns.OSConfig{Nameservers: []netip.Addr{{}}}
	if !b.isConfigNonNilAndDifferent(b.lastCfg, newDCfg) {
		t.Fatal("expected true when dns.OSConfig changed")
	}

	// Empty interface (network lost) should trigger update
	b.lastCfg = &router.Config{}
	b.lastDNSCfg = &dns.OSConfig{}
	b.appliedUnderlyingIface = "wlan0"
	b.lastUnderlyingIface = ""
	if !b.isConfigNonNilAndDifferent(b.lastCfg, b.lastDNSCfg) {
		t.Fatal("expected true when interface lost (empty string)")
	}

	// After applying empty interface
	b.appliedUnderlyingIface = ""
	if b.isConfigNonNilAndDifferent(b.lastCfg, b.lastDNSCfg) {
		t.Fatal("expected false after applied interface updated to empty")
	}
}

// Test that reflect.DeepEqual works correctly for router.Config and dns.OSConfig
func TestReflectDeepEqual(t *testing.T) {
	cfg1 := &router.Config{}
	cfg2 := &router.Config{}
	if !reflect.DeepEqual(cfg1, cfg2) {
		t.Fatal("empty router.Config should be equal")
	}

	cfg1.Routes = []netipx.IPPrefix{{}}
	if reflect.DeepEqual(cfg1, cfg2) {
		t.Fatal("router.Config with routes should not equal empty")
	}

	dns1 := &dns.OSConfig{}
	dns2 := &dns.OSConfig{}
	if !reflect.DeepEqual(dns1, dns2) {
		t.Fatal("empty dns.OSConfig should be equal")
	}
}