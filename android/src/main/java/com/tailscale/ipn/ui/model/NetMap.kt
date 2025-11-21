// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import kotlinx.serialization.Serializable

class Netmap {
  @Serializable
  data class NetworkMap(
      var SelfNode: Tailcfg.Node,
      var Peers: List<Tailcfg.Node>? = null,
      var Domain: String,
      var UserProfiles: Map<String, Tailcfg.UserProfile>,
      var TKAEnabled: Boolean,
      var DNS: Tailcfg.DNSConfig? = null,
      var AllCaps: List<String> = emptyList()
  ) {
    // Keys are tailcfg.UserIDs thet get stringified
    // Helpers
    fun currentUserProfile(): Tailcfg.UserProfile? {
      return userProfile(User())
    }

    fun User(): UserID {
      return SelfNode.User
    }

    fun userProfile(id: Long): Tailcfg.UserProfile? {
      return UserProfiles[id.toString()]
    }

    fun getPeer(id: StableNodeID): Tailcfg.Node? {
      if (id == SelfNode.StableID) {
        return SelfNode
      }
      return Peers?.find { it.StableID == id }
    }

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is NetworkMap) return false

      return SelfNode == other.SelfNode &&
          Peers == other.Peers &&
          User() == other.User() &&
          Domain == other.Domain &&
          UserProfiles == other.UserProfiles &&
          TKAEnabled == other.TKAEnabled
    }

    fun hasCap(capability: String): Boolean {
      return AllCaps.contains(capability)
    }
  }
}
