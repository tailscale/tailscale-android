// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import com.tailscale.ipn.ui.model.Ipn.Notify
import com.tailscale.ipn.ui.model.IpnState
import com.tailscale.ipn.ui.model.Tailcfg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
  fun initialStatusReplacesPreviousSelfWithoutSelfChange() {
    val previous = SelfNodeUpdate(revision = 7, stableID = "A")
    val notify = Notify(InitialStatus = IpnState.Status(Self = IpnState.PeerStatus(ID = "B")))

    assertEquals(
        SelfNodeUpdate(revision = 8, stableID = "B"),
        notify.nextSelfNodeUpdate(previous),
    )
  }

  @Test
  fun initialStatusWithoutSelfPublishesExplicitMissingSelf() {
    val previous = SelfNodeUpdate(revision = 7, stableID = "A")
    val notify = Notify(InitialStatus = IpnState.Status(Self = null))

    // A non-null update with a null identity clears downstream caches.
    assertEquals(
        SelfNodeUpdate(revision = 8, stableID = null),
        notify.nextSelfNodeUpdate(previous),
    )
  }

  @Test
  fun identicalSelfChangesStillAdvanceRevision() {
    val notify = Notify(SelfChange = Tailcfg.Node(StableID = "A"))

    val first = notify.nextSelfNodeUpdate(null)
    val second = notify.nextSelfNodeUpdate(first)

    assertEquals(SelfNodeUpdate(revision = 1, stableID = "A"), first)
    assertEquals(SelfNodeUpdate(revision = 2, stableID = "A"), second)
  }

  @Test
  fun unrelatedNotificationPreservesPreviousUpdate() {
    val previous = SelfNodeUpdate(revision = 7, stableID = "A")

    assertSame(previous, Notify().nextSelfNodeUpdate(previous))
  }
}
