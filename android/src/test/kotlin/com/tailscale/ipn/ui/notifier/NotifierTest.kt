// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import com.tailscale.ipn.ui.model.Ipn.Notify
import com.tailscale.ipn.ui.model.IpnState
import com.tailscale.ipn.ui.model.Tailcfg
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifierTest {
  @Test
  fun unrelatedNotificationsDoNotRequireNetmapUpdate() {
    assertFalse(Notify().hasNetworkMapChanges())
    assertFalse(Notify(State = 5).hasNetworkMapChanges())
  }

  @Test
  fun peerChangesRequireNetmapUpdate() {
    assertTrue(Notify(PeersRemoved = listOf(123L)).hasNetworkMapChanges())
    assertTrue(Notify(PeersChanged = listOf(Tailcfg.Node())).hasNetworkMapChanges())
  }

  @Test
  fun selfChangeRequiresNetmapUpdate() {
    assertTrue(Notify(SelfChange = Tailcfg.Node()).hasNetworkMapChanges())
  }

  @Test
  fun initialStatusRequiresNetmapUpdate() {
    assertTrue(Notify(InitialStatus = IpnState.Status()).hasNetworkMapChanges())
  }

  @Test
  fun userProfilesRequireNetmapUpdate() {
    assertTrue(Notify(UserProfiles = mapOf("123" to Tailcfg.UserProfile())).hasNetworkMapChanges())
  }

  @Test
  fun emptyPeerChangesDoNotRequireNetmapUpdate() {
    assertFalse(Notify(PeersChanged = emptyList()).hasNetworkMapChanges())
    assertFalse(Notify(PeersRemoved = emptyList()).hasNetworkMapChanges())
  }

  @Test
  fun initialNotificationContainsSessionID() {
    val notify = Json.decodeFromString<Notify>("""{"SessionID":"abc123","State":6}""")

    assertEquals("abc123", notify.SessionID)
    assertFalse(notify.Suspended)
  }

  @Test
  fun suspendedNotificationIsDecoded() {
    val notify = Json.decodeFromString<Notify>("""{"Suspended":true}""")

    assertTrue(notify.Suspended)
    assertNull(notify.SessionID)
  }
}
