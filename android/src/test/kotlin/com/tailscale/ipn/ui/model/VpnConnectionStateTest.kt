// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnConnectionStateTest {
  private val failure = DisconnectReason.BackendFailure("VPN setup failed")

  @Test
  fun runningBackendWaitsForTunnelBeforeShowingConnectedScreen() {
    val starting = VpnConnectionState(Ipn.State.Running, active = false, disconnectReason = null)
    assertEquals(Ipn.State.Starting, starting.state)
    assertTrue(starting.shouldStartAutomatically)

    val connected = starting.copy(active = true)
    assertEquals(Ipn.State.Running, connected.state)
    assertFalse(connected.shouldStartAutomatically)
  }

  @Test
  fun setupFailureShowsDisconnectedBeforeBackendReportsStopped() {
    val failed = VpnConnectionState(Ipn.State.Running, active = false, disconnectReason = failure)
    assertEquals(Ipn.State.Stopped, failed.state)
    assertFalse(
        "A queued automatic start must be rejected after failure",
        failed.shouldStartAutomatically,
    )

    val stopped = failed.copy(backendState = Ipn.State.Stopped)
    assertEquals(Ipn.State.Stopped, stopped.state)
    assertFalse(stopped.shouldStartAutomatically)
  }

  @Test
  fun failureStaysDisconnectedUntilRetryEstablishesTunnel() {
    val retry = VpnConnectionState(Ipn.State.Starting, active = false, disconnectReason = failure)
    assertEquals(Ipn.State.Stopped, retry.state)
    assertFalse(retry.shouldStartAutomatically)

    val connected =
        retry.copy(backendState = Ipn.State.Running, active = true, disconnectReason = null)
    assertEquals(Ipn.State.Running, connected.state)
    assertFalse(connected.shouldStartAutomatically)
  }

  @Test
  fun healthFailureStopsAutomaticStartsBeforeServiceDisconnectCallback() {
    val warning =
        Health.UnhealthyState(
            WarnableCode = "android-vpn-update-failed",
            Severity = Health.Severity.high,
            Title = "VPN update failed",
            Text = "VPN setup failed",
        )
    val failed =
        VpnConnectionState(
            Ipn.State.Running,
            active = false,
            disconnectReason = null,
            health = Health.State(Warnings = mapOf(warning.WarnableCode to warning)),
        )
    assertEquals(Ipn.State.Stopped, failed.state)
    assertFalse(failed.shouldStartAutomatically)

    // Success can reach the activity before the bus delivers the cleared warning.
    val connected = failed.copy(active = true)
    assertEquals(Ipn.State.Running, connected.state)
    assertFalse(connected.shouldStartAutomatically)
  }

  @Test
  fun failurePreservesLoginAndAuthorizationScreens() {
    for (state in
        listOf(Ipn.State.NeedsLogin, Ipn.State.NeedsMachineAuth, Ipn.State.InUseOtherUser)) {
      val connection = VpnConnectionState(state, active = false, disconnectReason = failure)
      assertEquals(state, connection.state)
      assertFalse(connection.shouldStartAutomatically)
    }
  }
}
