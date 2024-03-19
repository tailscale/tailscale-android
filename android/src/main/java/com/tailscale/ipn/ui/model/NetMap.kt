// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import kotlinx.serialization.Serializable

class Netmap {
  @Serializable
  data class NetworkMap(
      var SelfNode: Tailcfg.Node,
      var NodeKey: KeyNodePublic,
      var Peers: List<Tailcfg.Node>? = null,
      var Expiry: Time,
      var Domain: String,
      var UserProfiles: Map<String, Tailcfg.UserProfile>,
      var TKAEnabled: Boolean,
      var DNS: Tailcfg.DNSConfig? = null
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
          NodeKey == other.NodeKey &&
          Peers == other.Peers &&
          Expiry == other.Expiry &&
          User() == other.User() &&
          Domain == other.Domain &&
          UserProfiles == other.UserProfiles &&
          TKAEnabled == other.TKAEnabled
    }
  }
}
