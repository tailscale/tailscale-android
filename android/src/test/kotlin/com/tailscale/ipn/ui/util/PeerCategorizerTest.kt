// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.util.set
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PeerCategorizerTest {

  @Before
  fun setUp() {
    // Ensure no MDM hiding is active for these tests.
    MDMSettings.hiddenNetworkDevices.flow.set(SettingState(emptyList(), false))
  }

  private fun node(
      stableID: String,
      userID: Long,
      name: String,
      isMullvad: Boolean = false
  ): Tailcfg.Node {
    return Tailcfg.Node(
        StableID = stableID,
        User = userID,
        Name = name,
        Hostinfo = Tailcfg.Hostinfo(OS = if (isMullvad) null else "linux"))
  }

  private fun userProfile(id: Long, name: String): Pair<String, Tailcfg.UserProfile> {
    return id.toString() to Tailcfg.UserProfile(ID = id, DisplayName = name, LoginName = name)
  }

  private fun networkMap(self: Tailcfg.Node, peers: List<Tailcfg.Node>): Netmap.NetworkMap {
    val profiles =
        (peers + self)
            .map { it.User }
            .distinct()
            .associate { userProfile(it, "user-$it") }
    return Netmap.NetworkMap(
        SelfNode = self,
        Peers = peers,
        Domain = "example.com",
        UserProfiles = profiles,
        TKAEnabled = false)
  }

  @Test
  fun duplicateStableIDsAreDeduplicated() {
    val self = node(stableID = "n-self", userID = 1, name = "self")
    val peers =
        listOf(
            node(stableID = "n-dup", userID = 2, name = "first-dup"),
            node(stableID = "n-dup", userID = 2, name = "second-dup"),
            node(stableID = "", userID = 2, name = "empty-a"),
            node(stableID = "", userID = 3, name = "empty-b"),
            node(stableID = "n-unique", userID = 3, name = "unique"))

    val netmap = networkMap(self, peers)
    val categorizer = PeerCategorizer()
    categorizer.regenerateGroupedPeers(netmap)

    val allPeers = categorizer.peerSets.flatMap { it.peers }
    val allStableIDs = allPeers.map { it.StableID }

    assertEquals(4, allPeers.size)
    assertEquals(4, allStableIDs.toSet().size)
    assertTrue(allStableIDs.contains("n-self"))
    assertTrue(allStableIDs.contains("n-dup"))
    assertTrue(allStableIDs.contains(""))
    assertTrue(allStableIDs.contains("n-unique"))

    val dupPeer = allPeers.first { it.StableID == "n-dup" }
    assertEquals("first-dup", dupPeer.Name)
  }

  @Test
  fun selfNodeWinsWhenPeerSharesStableID() {
    val self = node(stableID = "n-shared", userID = 1, name = "self")
    val peers = listOf(node(stableID = "n-shared", userID = 2, name = "other"))

    val netmap = networkMap(self, peers)
    val categorizer = PeerCategorizer()
    categorizer.regenerateGroupedPeers(netmap)

    val allPeers = categorizer.peerSets.flatMap { it.peers }
    assertEquals(1, allPeers.size)
    assertEquals("self", allPeers.first().Name)
  }
}
