// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import "sync"

// ifname is the interface name retrieved from LinkProperties on network change.
// An empty string is used if there is no network available.
type networkState struct {
	ifname        string
	networkHandle int64
	gateway       string
	dnsConfig     string
}

// networkStateNotifier keeps the latest network state and signals the
// consumer when it changes. Notifications can be dropped because the
// consumer always reads the latest state.
type networkStateNotifier struct {
	mu      sync.Mutex
	latest  networkState
	pending chan struct{}
}

func newNetworkStateNotifier() *networkStateNotifier {
	return &networkStateNotifier{pending: make(chan struct{}, 1)}
}

func (m *networkStateNotifier) send(change networkState) {
	m.mu.Lock()
	m.latest = change
	m.mu.Unlock()
	select {
	case m.pending <- struct{}{}:
	default:
	}
}

func (m *networkStateNotifier) peek() networkState {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.latest
}
