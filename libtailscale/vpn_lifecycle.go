// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import "log"

// configureVPN reports the result to the engine before tearing down a failed VPN.
// State changes and service requests do not have an engine reply channel.
func configureVPN(apply func() error, reply chan<- error, failed func(error)) {
	err := apply()
	if reply != nil {
		reply <- err
	}
	if err != nil {
		failed(err)
	}
}

type vpnDisconnector interface {
	UpdateVpnStatus(bool)
	DisconnectVPN(errorMessage string)
}

// stopVPNOnFailure must not block the VPN loop: stopping the backend can issue
// configuration updates that need the same loop to reply before it can finish.
func stopVPNOnFailure(service vpnDisconnector, failure error, stopBackend func() error) {
	// Publish the inactive status on the VPN loop, before a retry can become
	// active. Delayed Android service destruction must not publish it again.
	service.UpdateVpnStatus(false)
	go func() {
		if err := stopBackend(); err != nil {
			log.Printf("stopping backend after VPN failure: %v", err)
		}
		service.DisconnectVPN(failure.Error())
	}()
}
