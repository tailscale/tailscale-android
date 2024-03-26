// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"sync"
)

var (
	// onVPNPrepared is notified when VpnService.prepare succeeds.
	onVPNPrepared = make(chan struct{}, 1)
	// onVPNClosed is notified when VpnService.prepare fails, or when
	// the a running VPN connection is closed.
	onVPNClosed = make(chan struct{}, 1)
	// onVPNRevoked is notified whenever the VPN service is revoked.
	onVPNRevoked = make(chan struct{}, 1)

	// onVPNRequested receives global IPNService references when
	// a VPN connection is requested.
	onVPNRequested = make(chan IPNService)
	// onDisconnect receives global IPNService references when
	// disconnecting.
	onDisconnect = make(chan IPNService)

	// onGoogleToken receives google ID tokens.
	onGoogleToken = make(chan string)

	// onDNSConfigChanged is notified when the network changes and the DNS config needs to be updated.
	onDNSConfigChanged = make(chan struct{}, 1)
)

func OnDnsConfigChanged() {
	select {
	case onDNSConfigChanged <- struct{}{}:
	default:
	}
}

func notifyVPNPrepared() {
	select {
	case onVPNPrepared <- struct{}{}:
	default:
	}
}

func notifyVPNRevoked() {
	select {
	case onVPNRevoked <- struct{}{}:
	default:
	}
}

func notifyVPNClosed() {
	select {
	case onVPNClosed <- struct{}{}:
	default:
	}
}

var android struct {
	// mu protects all fields of this structure. However, once a
	// non-nil jvm is returned from javaVM, all the other fields may
	// be accessed unlocked.
	mu sync.Mutex

	// appCtx is the global Android App context.
	appCtx AppContext
}
