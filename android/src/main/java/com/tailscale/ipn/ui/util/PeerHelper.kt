// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.model.UserID

data class PeerSet(val user: Tailcfg.UserProfile?, val peers: List<Tailcfg.Node>)

class PeerCategorizer {
  var peerSets: List<PeerSet> = emptyList()
  var lastSearchResult: List<PeerSet> = emptyList()
  var lastSearchTerm: String = ""

  fun regenerateGroupedPeers(netmap: Netmap.NetworkMap) {
    val peers: List<Tailcfg.Node> = netmap.Peers ?: return
    val selfNode = netmap.SelfNode
    var grouped = mutableMapOf<UserID, MutableList<Tailcfg.Node>>()

    for (peer in (peers + selfNode)) {
      // (jonathan) TODO: MDM -> There are a number of MDM settings to hide devices from the user
      // (jonathan) TODO: MDM -> currentUser, otherUsers, taggedDevices
      val userId = peer.User

      // Mullvad based nodes should not be shown in the peer list
      if (!peer.isMullvadNode) {
        if (!grouped.containsKey(userId)) {
          grouped[userId] = mutableListOf()
        }
        grouped[userId]?.add(peer)
      }
    }

    val me = netmap.currentUserProfile()

    peerSets =
        grouped
            .map { (userId, peers) ->
              val profile = netmap.userProfile(userId)
              PeerSet(
                  profile,
                  peers.sortedWith { a, b ->
                    when {
                      a.StableID == b.StableID -> 0
                      a.isSelfNode(netmap) -> -1
                      b.isSelfNode(netmap) -> 1
                      else ->
                          (a.ComputedName?.lowercase() ?: "").compareTo(
                              b.ComputedName?.lowercase() ?: "")
                    }
                  })
            }
            .sortedBy {
              if (it.user?.ID == me?.ID) {
                ""
              } else {
                it.user?.DisplayName?.lowercase() ?: "unknown user"
              }
            }
  }

  fun groupedAndFilteredPeers(searchTerm: String = ""): List<PeerSet> {
    if (searchTerm.isEmpty()) {
      return peerSets
    }

    if (searchTerm == this.lastSearchTerm) {
      return lastSearchResult
    }

    // We can optimize out typing... If the search term starts with the last search term, we can
    // just search the last result
    val setsToSearch =
        if (this.lastSearchTerm.isNotEmpty() && searchTerm.startsWith(this.lastSearchTerm))
            lastSearchResult
        else peerSets
    this.lastSearchTerm = searchTerm

    val matchingSets =
        setsToSearch
            .map { peerSet ->
              val user = peerSet.user
              val peers = peerSet.peers

              val userMatches = user?.DisplayName?.contains(searchTerm, ignoreCase = true) ?: false
              if (userMatches) {
                return@map peerSet
              }

              val matchingPeers =
                  peers.filter { it.displayName.contains(searchTerm, ignoreCase = true) }
              if (matchingPeers.isNotEmpty()) {
                PeerSet(user, matchingPeers)
              } else {
                null
              }
            }
            .filterNotNull()
    lastSearchResult = matchingSets
    return matchingSets
  }
}
