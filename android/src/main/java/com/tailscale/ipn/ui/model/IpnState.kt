// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import java.net.URL
import kotlinx.serialization.Serializable

class IpnState {
  @Serializable
  data class PeerStatusLite(
      val RxBytes: Long,
      val TxBytes: Long,
      val LastHandshake: String,
      val NodeKey: String,
  )

  @Serializable
  data class PeerStatus(
      val ID: StableNodeID,
      val HostName: String,
      val DNSName: String,
      val TailscaleIPs: List<Addr>? = null,
      val Tags: List<String>? = null,
      val PrimaryRoutes: List<String>? = null,
      val Addrs: List<String>? = null,
      val CurAddr: String? = null,
      val Relay: String? = null,
      val PeerRelay: String? = null,
      val Online: Boolean,
      val ExitNode: Boolean,
      val ExitNodeOption: Boolean,
      val Active: Boolean,
      val PeerAPIURL: List<String>? = null,
      val Capabilities: List<String>? = null,
      val SSH_HostKeys: List<String>? = null,
      val ShareeNode: Boolean? = null,
      val Expired: Boolean? = null,
      val Location: Tailcfg.Location? = null,
  ) {
    fun computedName(status: Status): String {
      val name = DNSName
      val suffix = status.CurrentTailnet?.MagicDNSSuffix

      suffix ?: return name

      if (!(name.endsWith("." + suffix + "."))) {
        return name
      }

      return name.dropLast(suffix.count() + 2)
    }
  }

  @Serializable
  data class ExitNodeStatus(
      val ID: StableNodeID,
      val Online: Boolean,
      val TailscaleIPs: List<Prefix>? = null,
  )

  @Serializable
  data class TailnetStatus(
      val Name: String,
      val MagicDNSSuffix: String,
      val MagicDNSEnabled: Boolean,
  )

  @Serializable
  data class Status(
      val Version: String,
      val TUN: Boolean,
      val BackendState: String,
      val AuthURL: String,
      val TailscaleIPs: List<Addr>? = null,
      val Self: PeerStatus? = null,
      val ExitNodeStatus: ExitNodeStatus? = null,
      val Health: List<String>? = null,
      val CurrentTailnet: TailnetStatus? = null,
      val CertDomains: List<String>? = null,
      val Peer: Map<String, PeerStatus>? = null,
      val User: Map<String, Tailcfg.UserProfile>? = null,
      val ClientVersion: Tailcfg.ClientVersion? = null,
  )

  @Serializable
  data class NetworkLockStatus(
      var Enabled: Boolean? = null,
      var PublicKey: String? = null,
      var NodeKey: String? = null,
      var NodeKeySigned: Boolean? = null,
      var FilteredPeers: List<TKAFilteredPeer>? = null,
      var StateID: ULong? = null,
      var TrustedKeys: List<TKAKey>? = null
  ) {

    fun IsPublicKeyTrusted(): Boolean {
      return TrustedKeys?.any { it.Key == PublicKey } == true
    }
  }

  @Serializable
  data class TKAFilteredPeer(
      var Name: String,
      var TailscaleIPs: List<Addr>,
      var NodeKey: String,
  )

  @Serializable data class TKAKey(var Key: String)

  @Serializable
  data class PingResult(
      var IP: Addr,
      var Err: String,
      var LatencySeconds: Double,
  )
}

class IpnLocal {
  @Serializable
  data class LoginProfile(
      var ID: String,
      val Name: String,
      val Key: String,
      val UserProfile: Tailcfg.UserProfile,
      val NetworkProfile: Tailcfg.NetworkProfile? = null,
      val LocalUserID: String,
      var ControlURL: String? = null,
  ) {
    fun isEmpty(): Boolean {
      return ID.isEmpty()
    }

    // Returns true if the profile uses a custom control server (not Tailscale SaaS).
    private fun isUsingCustomControlServer(): Boolean {
      return ControlURL != null && ControlURL != "https://controlplane.tailscale.com"
    }

    // Returns the hostname of the custom control server, if any was set.
    //
    // Returns null if the ControlURL provided by the backend is an invalid URL, and
    // a hostname cannot be extracted.
    fun customControlServerHostname(): String? {
      if (!isUsingCustomControlServer()) return null

      return try {
        URL(ControlURL).host
      } catch (e: Exception) {
        null
      }
    }
  }
}
