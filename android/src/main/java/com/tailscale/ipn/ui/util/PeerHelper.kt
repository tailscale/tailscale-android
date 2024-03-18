// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause


package com.tailscale.ipn.ui.util

import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.model.UserID
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch


data class PeerSet(val user: Tailcfg.UserProfile?, val peers: List<Tailcfg.Node>)

typealias GroupedPeers = MutableMap<UserID, MutableList<Tailcfg.Node>>

class PeerCategorizer(scope: CoroutineScope) {
    var peerSets: List<PeerSet> = emptyList()
    var lastSearchResult: List<PeerSet> = emptyList()
    var searchTerm: String = ""

    // Keep the peer sets current while the model is active
    init {
        scope.launch {
            Notifier.netmap.collect { netmap ->
                netmap?.let {
                    peerSets = regenerateGroupedPeers(netmap)
                    lastSearchResult = peerSets
                } ?: run {
                    peerSets = emptyList()
                    lastSearchResult = emptyList()

                }
            }
        }
    }

    private fun regenerateGroupedPeers(netmap: Netmap.NetworkMap): List<PeerSet> {
        val peers: List<Tailcfg.Node> = netmap.Peers ?: return emptyList()
        val selfNode = netmap.SelfNode
        var grouped = mutableMapOf<UserID, MutableList<Tailcfg.Node>>()

        for (peer in (peers + selfNode)) {
            // (jonathan) TODO: MDM -> There are a number of MDM settings to hide devices from the user
            // (jonathan) TODO: MDM -> currentUser, otherUsers, taggedDevices
            val userId = peer.User

            if (!grouped.containsKey(userId)) {
                grouped[userId] = mutableListOf()
            }
            grouped[userId]?.add(peer)
        }

        val me = netmap.currentUserProfile()

        val peerSets = grouped.map { (userId, peers) ->
            val profile = netmap.userProfile(userId)
            PeerSet(profile, peers.sortedBy { it.ComputedName })
        }.sortedBy {
            if (it.user?.ID == me?.ID) {
                ""
            } else {
                it.user?.DisplayName ?: "Unknown User"
            }
        }

        return peerSets
    }

    fun groupedAndFilteredPeers(searchTerm: String = ""): List<PeerSet> {
        if (searchTerm.isEmpty()) {
            return peerSets
        }

        if (searchTerm == this.searchTerm) {
            return lastSearchResult
        }

        // We can optimize out typing... If the search term starts with the last search term, we can just search the last result
        val setsToSearch =
            if (searchTerm.startsWith(this.searchTerm)) lastSearchResult else peerSets
        this.searchTerm = searchTerm

        val matchingSets = setsToSearch.map { peerSet ->
            val user = peerSet.user
            val peers = peerSet.peers

            val userMatches = user?.DisplayName?.contains(searchTerm, ignoreCase = true) ?: false
            if (userMatches) {
                return@map peerSet
            }

            val matchingPeers =
                peers.filter { it.ComputedName.contains(searchTerm, ignoreCase = true) }
            if (matchingPeers.isNotEmpty()) {
                PeerSet(user, matchingPeers)
            } else {
                null
            }
        }.filterNotNull()

        return matchingSets
    }

}