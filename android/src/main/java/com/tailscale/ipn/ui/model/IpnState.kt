// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.model

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
            val Online: Boolean,
            val ExitNode: Boolean,
            val ExitNodeOption: Boolean,
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
            var Enabled: Boolean,
            var PublicKey: String,
            var NodeKey: String,
            var NodeKeySigned: Boolean,
            var FilteredPeers: List<TKAFilteredPeer>? = null,
            var StateID: ULong? = null,
    )

    @Serializable
    data class TKAFilteredPeer(
            var Name: String,
            var TailscaleIPs: List<Addr>,
            var NodeKey: String,
    )

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
    )
}
