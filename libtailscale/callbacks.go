// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"sync"
)

var (
	// onVPNRequested receives global IPNService references when
	// a VPN connection is requested.
	onVPNRequested = make(chan IPNService)
	// onDisconnect receives global IPNService references when
	// disconnecting.
	onDisconnect = make(chan IPNService)

	// onGoogleToken receives google ID tokens.
	onGoogleToken = make(chan string)

	// networkStates retains the most recent Android network update until the
	// backend can process it. The channel is only a wake-up signal.
	networkStates = newNetworkStateNotifier()

	// onLog receives Android logs to be sent to the logger
	onLog = make(chan string, 10)

	// onShareFileHelper receives ShareFileHelper references when the app is initialized so that files can be received via Storage Access Framework
	onShareFileHelper = make(chan ShareFileHelper, 1)
)

// OnAndroidNetworkChanged receives the latest Android network configuration.
func OnAndroidNetworkChanged(ifname string, networkHandle int64, gateway, dnsConfig string) {
	networkStates.send(networkState{
		ifname:        ifname,
		networkHandle: networkHandle,
		gateway:       gateway,
		dnsConfig:     dnsConfig,
	})
}

var android struct {
	// mu protects all fields of this structure. However, once a
	// non-nil jvm is returned from javaVM, all the other fields may
	// be accessed unlocked.
	mu sync.Mutex

	// appCtx is the global Android App context.
	appCtx AppContext
}
