// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

internal data class VpnConnectionState(
    val backendState: Ipn.State,
    val active: Boolean,
    val disconnectReason: DisconnectReason?,
    val health: Health.State? = null,
) {
  // The health warning can arrive before the failed service finishes stopping.
  val hasFailed: Boolean
    get() =
        disconnectReason is DisconnectReason.BackendFailure ||
            health?.Warnings?.get("android-vpn-update-failed") != null

  // Running describes the engine, which can start before Android creates a tunnel.
  val state: Ipn.State
    get() =
        when {
          !active &&
              hasFailed &&
              (backendState == Ipn.State.Starting || backendState == Ipn.State.Running) ->
              Ipn.State.Stopped
          backendState == Ipn.State.Running && !active -> Ipn.State.Starting
          else -> backendState
        }

  val shouldStartAutomatically: Boolean
    get() = backendState == Ipn.State.Running && !active && !hasFailed
}
