// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import "sync"

// ifname is the interface name retrieved from LinkProperties on network change.
// An empty string is used if there is no network available.
type networkChange struct {
	ifname        string
	networkHandle int64
	gateway       string
	dnsConfig     string
}

type networkChangeMailbox struct {
	mu     sync.Mutex
	latest networkChange
	wake   chan struct{}
}

func newNetworkChangeMailbox() *networkChangeMailbox {
	return &networkChangeMailbox{wake: make(chan struct{}, 1)}
}

func (m *networkChangeMailbox) send(change networkChange) {
	m.mu.Lock()
	m.latest = change
	m.mu.Unlock()
	select {
	case m.wake <- struct{}{}:
	default:
	}
}

func (m *networkChangeMailbox) take() networkChange {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.latest
}
