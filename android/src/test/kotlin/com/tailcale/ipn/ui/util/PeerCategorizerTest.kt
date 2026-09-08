// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailcale.ipn.ui.util

import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.util.PeerCategorizer
import org.junit.Assert.assertEquals
import org.junit.Test

class PeerCategorizerTest {

  private val userID = 1L

  private fun node(stableID: String, name: String = "node-$stableID") =
      Tailcfg.Node(
          ID = stableID.toLong(),
          StableID = stableID,
          Name = name,
          User = userID,
          ComputedName = name)

  private fun netmap(self: Tailcfg.Node, peers: List<Tailcfg.Node>) =
      Netmap.NetworkMap(
          SelfNode = self,
          Peers = peers,
          Domain = "example.com",
          UserProfiles =
              mapOf(
                  userID.toString() to
                      Tailcfg.UserProfile(
                          ID = userID, DisplayName = "Test User", LoginName = "test@example.com")),
          TKAEnabled = false)

  private fun stableIDsFrom(netmap: Netmap.NetworkMap): List<String> {
    val categorizer = PeerCategorizer()
    categorizer.regenerateGroupedPeers(netmap)
    return categorizer.peerSets.flatMap { peerSet -> peerSet.peers }.map { it.StableID }
  }

  /**
   * A control server may hand us a netmap whose Peers list already contains this node. The node
   * must still be listed exactly once: MainView keys its LazyColumn by StableID, and a duplicate
   * key crashes the app.
   */
  @Test
  fun selfNodeAlreadyInPeersIsNotDuplicated() {
    val self = node("20", name = "self")
    val ids = stableIDsFrom(netmap(self, listOf(node("6"), node("7"), self)))

    assertEquals("StableIDs must be unique", ids.distinct(), ids)
    assertEquals(listOf("20", "6", "7").sorted(), ids.sorted())
  }

  /** The ordinary case: self is absent from Peers and still gets listed. */
  @Test
  fun selfNodeAbsentFromPeersIsStillListed() {
    val self = node("20", name = "self")
    val ids = stableIDsFrom(netmap(self, listOf(node("6"), node("7"))))

    assertEquals("StableIDs must be unique", ids.distinct(), ids)
    assertEquals(listOf("20", "6", "7").sorted(), ids.sorted())
  }
}
