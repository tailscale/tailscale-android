// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.util.fastAny
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Favorites
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.model.UserID

private const val FAVORITES_ID: UserID = -1

data class PeerSet(
    val id: UserID,
    val title: String?,
    val nodes: List<Tailcfg.Node>,
) {
  companion object {
    fun create(id: UserID, title: String?, nodes: List<Tailcfg.Node>): PeerSet? {
      if (nodes.isEmpty()) return null
      return PeerSet(id, title, nodes)
    }
  }

  @Composable
  fun sectionTitle(): String {
    return if (id == FAVORITES_ID) {
      stringResource(id = R.string.pinned_devices)
    } else {
      title ?: stringResource(id = R.string.unknown_user)
    }
  }
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

  fun regenerateGroupedPeers(
      netmap: Netmap.NetworkMap,
      favorites: Favorites,
  ) {
    val peers: List<Tailcfg.Node> = netmap.Peers ?: return
    val selfNode = netmap.SelfNode
    val grouped = mutableMapOf<UserID, MutableList<Tailcfg.Node>>()
    val favoriteDeviceIds = favorites.deviceIds.toSet()

    val mdm = MDMSettings.hiddenNetworkDevices.flow.value.value
    val hideMyDevices = mdm?.contains("current-user") ?: false
    val hideOtherDevices = mdm?.contains("other-users") ?: false
    val hideTaggedDevices = mdm?.contains("tagged-devices") ?: false

    val me = netmap.currentUserProfile()

    for (peer in (peers + selfNode)) {
      val userId = peer.User
      val profile = netmap.userProfile(userId)
      val groupId = if (favoriteDeviceIds.contains(peer.StableID)) FAVORITES_ID else peer.User

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

      if (!grouped.containsKey(groupId)) {
        grouped[groupId] = mutableListOf()
      }

      grouped[groupId]?.add(peer)
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
            .sortedWith(
                compareBy(
                    { it.id != FAVORITES_ID }, // keep pinned at top
                    { if (it.id == me?.ID) "" else it.title?.lowercase() ?: "unknown user" },
                )
            )
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

      if (peerSet.title?.contains(searchTerm, ignoreCase = true) ?: false) {
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
