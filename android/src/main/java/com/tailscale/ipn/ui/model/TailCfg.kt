// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import kotlinx.serialization.Serializable

class Tailcfg {
    // Currently unused
//    @Serializable
//    data class ClientVersion(
//            var RunningLatest: Boolean? = null,
//            var LatestVersion: String? = null,
//            var UrgentSecurityUpdate: Boolean? = null,
//            var Notify: Boolean? = null,
//            var NotifyURL: String? = null,
//            var NotifyText: String? = null
//    )

    @Serializable
    data class UserProfile(
        val ID: Long,
        val DisplayName: String,
        val LoginName: String,
        val ProfilePicURL: String? = null,
    ) {
        fun isTaggedDevice(): Boolean {
            return LoginName == "tagged-devices"
        }
    }

    @Serializable
    data class Hostinfo(
//        var IPNVersion: String? = null, // Currently unused
//        var FrontendLogID: String? = null, // Currently unused
//        var BackendLogID: String? = null, // Currently unused
        var OS: String? = null,
//        var OSVersion: String? = null, // Currently unused
//        var Env: String? = null, // Currently unused
//        var Distro: String? = null, // Currently unused
//        var DistroVersion: String? = null, // Currently unused
//        var DistroCodeName: String? = null, // Currently unused
//        var Desktop: Boolean? = null, // Currently unused
//        var Package: String? = null, // Currently unused
//        var DeviceModel: String? = null, // Currently unused
//        var ShareeNode: Boolean? = null, // Currently unused
//        var Hostname: String? = null, // Currently unused
//        var ShieldsUp: Boolean? = null, // Currently unused
//        var NoLogsNoSupport: Boolean? = null, // Currently unused
//        var Machine: String? = null, // Currently unused
//        var RoutableIPs: List<Prefix>? = null, // Currently unused
//        var Services: List<Service>? = null, // Currently unused
        var Location: Location? = null,
    )

    @Serializable
    data class Node(
//        var ID: NodeID, // Currently unused
        var StableID: StableNodeID,
        var Name: String,
        var User: UserID,
//        var Sharer: UserID? = null, // Currently unused
//        var Key: KeyNodePublic, // Currently unused
        var KeyExpiry: String,
//        var Machine: MachineKey, // Currently unused
        var Addresses: List<Prefix>? = null,
        var AllowedIPs: List<Prefix>? = null,
//        var Endpoints: List<String>? = null, // Currently unused
        var Hostinfo: Hostinfo,
//        var Created: Time, // Currently unused
//        var LastSeen: Time? = null, // Currently unused
        var Online: Boolean? = null,
        var Capabilities: List<String>? = null,
        var ComputedName: String,
//        var ComputedNameWithHost: String // Currently unused
    ) {
        val isAdmin: Boolean
            get() = (Capabilities ?: emptyList()).contains("https://tailscale.com/cap/is-admin")

        // isExitNode reproduces the Go logic in local.go peerStatusFromNode
        val isExitNode: Boolean =
            AllowedIPs?.contains("0.0.0.0/0") ?: false && AllowedIPs?.contains("::/0") ?: false
    }

    // Currently unused
//    @Serializable
//    data class Service(var Proto: String, var Port: Int, var Description: String? = null)

    @Serializable
    data class NetworkProfile(var MagicDNSName: String? = null, var DomainName: String? = null)

    @Serializable
    data class Location(
        var Country: String? = null,
        var CountryCode: String? = null,
        var City: String? = null,
//        var CityCode: String? = null, // Currently unused
        var Priority: Int? = null
    )

    // Currently unused
//    @Serializable
//    data class DNSConfig(
//        var Resolvers: List<DnsType.Resolver>? = null,
//        var Routes: Map<String, List<DnsType.Resolver>?>? = null,
//        var FallbackResolvers: List<DnsType.Resolver>? = null,
//        var Domains: List<String>? = null,
//        var Nameservers: List<Addr>? = null
//    )
}
