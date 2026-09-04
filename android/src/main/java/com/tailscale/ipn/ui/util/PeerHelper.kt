// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.compose.ui.util.fastAny
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.model.UserID

data class PeerSet(
    val id: UserID,
    val title: String?,
    val nodes: List<Tailcfg.Node>,
) {
  companion object {
    const val FAVORITES_ID: UserID = -1

    fun create(id: UserID, title: String?, nodes: List<Tailcfg.Node>): PeerSet? =
        if (nodes.isEmpty()) null else PeerSet(id, title, nodes)
  }

  val isFavorite: Boolean
    get() = id == FAVORITES_ID
}

fun List<PeerSet>.withPinnedSection(pinnedIds: List<StableNodeID>): List<PeerSet> {
  val ids = pinnedIds.distinct()
  if (ids.isEmpty()) return this

  val pinned = ids.toSet()
  val byId = mutableMapOf<StableNodeID, Tailcfg.Node>()
  for (set in this) for (node in set.nodes) {
    if (node.StableID in pinned) byId[node.StableID] = node
  }

  val pinnedNodes = ids.mapNotNull { byId[it] }
  if (pinnedNodes.isEmpty()) return this

  val remaining = mapNotNull { set ->
    PeerSet.create(set.id, set.title, set.nodes.filterNot { it.StableID in pinned })
  }

  return listOf(PeerSet(PeerSet.FAVORITES_ID, null, pinnedNodes)) + remaining
}

private fun List<Tailcfg.Node>.nodeSort(netmap: Netmap.NetworkMap): List<Tailcfg.Node> {
  return this.sortedWith { a, b ->
    when {
      a.StableID == b.StableID -> 0
      a.isSelfNode(netmap) -> -1
      b.isSelfNode(netmap) -> 1
      else -> (a.ComputedName?.lowercase() ?: "").compareTo(b.ComputedName?.lowercase() ?: "")
    }
  }
}

class PeerCategorizer {
  var peerSets: List<PeerSet> = emptyList()
  var lastSearchResult: List<PeerSet> = emptyList()
  var lastSearchTerm: String = ""

  fun regenerateGroupedPeers(netmap: Netmap.NetworkMap) {
    val peers: List<Tailcfg.Node> = netmap.Peers.orEmpty()
    val selfNode = netmap.SelfNode
    val grouped = mutableMapOf<UserID, MutableList<Tailcfg.Node>>()

    val mdm = MDMSettings.hiddenNetworkDevices.flow.value.value
    val hideMyDevices = mdm?.contains("current-user") ?: false
    val hideOtherDevices = mdm?.contains("other-users") ?: false
    val hideTaggedDevices = mdm?.contains("tagged-devices") ?: false

    val me = netmap.currentUserProfile()

    for (peer in (peers + selfNode)) {
      val userId = peer.User
      val profile = netmap.userProfile(userId)

      // Mullvad nodes should not be shown in the peer list
      if (peer.isMullvadNode) {
        continue
      }

      // Hide devices based on MDM settings
      if (hideMyDevices && userId == me?.ID) {
        continue
      }

      if (hideOtherDevices && userId != me?.ID) {
        continue
      }

      if (hideTaggedDevices && (profile?.isTaggedDevice() == true)) {
        continue
      }

      if (!grouped.containsKey(userId)) {
        grouped[userId] = mutableListOf()
      }

      grouped[userId]?.add(peer)
    }

    peerSets =
        grouped
            .mapNotNull { (userId, peers) ->
              PeerSet.create(
                  userId,
                  netmap.userProfile(userId)?.DisplayName,
                  peers.nodeSort(netmap),
              )
            }
            .sortedBy { if (it.id == me?.ID) "" else it.title?.lowercase() ?: "unknown user" }

    lastSearchTerm = ""
    lastSearchResult = emptyList()
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

    val matchingSets = setsToSearch.mapNotNull { peerSet ->
      val peers = peerSet.nodes

      if (peerSet.title?.contains(searchTerm, ignoreCase = true) == true) {
        return@mapNotNull peerSet
      }

      val matchingPeers = peers.filter { peer ->
        val matchDisplay = peer.displayName.contains(searchTerm, ignoreCase = true)
        val matchAddress = peer.Addresses.orEmpty().fastAny { it.contains(searchTerm) }
        matchDisplay || matchAddress
      }

      if (matchingPeers.isNotEmpty()) PeerSet(peerSet.id, peerSet.title, matchingPeers) else null
    }

    lastSearchResult = matchingSets
    return matchingSets
  }
}
