// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.util

import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.model.UserID
import com.tailscale.ipn.ui.service.IpnModel


data class PeerSet(val user: Tailcfg.UserProfile?, val peers: List<Tailcfg.Node>)

class PeerCategorizer(val model: IpnModel) {
    fun groupedAndFilteredPeers(searchTerm: String = ""): List<PeerSet> {
        val netmap: Netmap.NetworkMap = model.netmap.value ?: return emptyList()
        val peers: List<Tailcfg.Node> = netmap.Peers ?: return emptyList()
        val selfNode = netmap.SelfNode

        val grouped = mutableMapOf<UserID, MutableList<Tailcfg.Node>>()
        for(peer in (peers + selfNode) ) {
            // (jonathan) TODO: MDM -> There are a number of MDM settings to hide devices from the user
            // (jonathan) TODO: MDM -> currentUser, otherUsers, taggedDevices

            val userId = peer.User
            if (searchTerm.isNotEmpty() && !peer.ComputedName.contains(searchTerm, ignoreCase = true)) {
                continue
            }
            if (!grouped.containsKey(userId)) {
                grouped[userId] = mutableListOf()
            }
            grouped[userId]?.add(peer)
        }
        var selfPeers = (grouped[selfNode.User] ?: emptyList()).sortedBy { it.ComputedName }
        grouped.remove(selfNode.User)

        val currentNode = selfPeers.first { it.ID == selfNode.ID }
        currentNode.let {
            selfPeers = selfPeers.filter { it.ID != currentNode.ID }
            selfPeers = listOf(currentNode) + selfPeers
        }

        val sorted = grouped.map { (userId, peers) ->
            val profile = netmap.userProfile(userId)
            PeerSet(profile, peers)
        }.sortedBy {
            it.user?.DisplayName ?: "Unknown User"
        }

        val me = netmap.currentUserProfile()
        return listOf(PeerSet(me, selfPeers)) + sorted
    }
}