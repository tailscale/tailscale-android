// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.model

import kotlinx.serialization.*

class Tailcfg {
    @Serializable
    data class ClientVersion(
            var RunningLatest: Boolean? = null,
            var LatestVersion: String? = null,
            var UrgentSecurityUpdate: Boolean? = null,
            var Notify: Boolean? = null,
            var NotifyURL: String? = null,
            var NotifyText: String? = null
    )

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
            var IPNVersion: String? = null,
            var FrontendLogID: String? = null,
            var BackendLogID: String? = null,
            var OS: String? = null,
            var OSVersion: String? = null,
            var Env: String? = null,
            var Distro: String? = null,
            var DistroVersion: String? = null,
            var DistroCodeName: String? = null,
            var Desktop: Boolean? = null,
            var Package: String? = null,
            var DeviceModel: String? = null,
            var ShareeNode: Boolean? = null,
            var Hostname: String? = null,
            var ShieldsUp: Boolean? = null,
            var NoLogsNoSupport: Boolean? = null,
            var Machine: String? = null,
            var RoutableIPs: List<Prefix>? = null,
            var Services: List<Service>? = null,
    )

    @Serializable
    data class Node(
            var ID: NodeID,
            var StableID: StableNodeID,
            var Name: String,
            var User: UserID,
            var Sharer: UserID? = null,
            var Key: KeyNodePublic,
            var KeyExpiry: String,
            var Machine: MachineKey,
            var Addresses: List<Prefix>? = null,
            var AllowedIPs: List<Prefix>? = null,
            var Endpoints: List<String>? = null,
            var Hostinfo: Hostinfo,
            var Created: Time,
            var LastSeen: Time? = null,
            var Online: Boolean? = null,
            var Capabilities: List<String>? = null,
            var ComputedName: String,
            var ComputedNameWithHost: String
    )

    @Serializable
    data class Service(var Proto: String, var Port: Int, var Description: String? = null) 

    @Serializable
    data class NetworkProfile(var MagicDNSName: String? = null, var DomainName: String? = null)

    @Serializable
    data class Location(
            var Country: String? = null,
            var CountryCode: String? = null,
            var City: String? = null,
            var CityCode: String? = null,
            var Priority: Int? = null
    )

    @Serializable
    data class DNSConfig(
            var Resolvers: List<DnsType.Resolver>? = null,
            var Routes: Map<String, List<DnsType.Resolver>?>? = null,
            var FallbackResolvers: List<DnsType.Resolver>? = null,
            var Domains: List<String>? = null,
            var Nameservers: List<Addr>? = null
    )
}
