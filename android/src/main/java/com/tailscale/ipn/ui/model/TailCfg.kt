// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.theme.off
import com.tailscale.ipn.ui.theme.on
import com.tailscale.ipn.ui.util.ComposableStringFormatter
import com.tailscale.ipn.ui.util.DisplayAddress
import com.tailscale.ipn.ui.util.TimeUtil
import com.tailscale.ipn.ui.util.flag
import com.tailscale.ipn.ui.viewModel.PeerSettingInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.Date

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
      var Location: Location? = null,
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
      var CapMap: Map<String, JsonElement?>? = null,
      var ComputedName: String?,
      var ComputedNameWithHost: String?
  ) {
    val isAdmin: Boolean
      get() =
          Capabilities?.contains("https://tailscale.com/cap/is-admin") == true ||
              CapMap?.contains("https://tailscale.com/cap/is-admin") == true

    // Derives the url to directly administer a node
    val nodeAdminUrl: String
      get() = primaryIPv4Address?.let { "${Links.ADMIN_URL}/machines/${it}" } ?: Links.ADMIN_URL

    val primaryIPv4Address: String?
      get() = displayAddresses.firstOrNull { it.type == DisplayAddress.addrType.V4 }?.address

    val primaryIPv6Address: String?
      get() = displayAddresses.firstOrNull { it.type == DisplayAddress.addrType.V6 }?.address

    // isExitNode reproduces the Go logic in local.go peerStatusFromNode
    val isExitNode: Boolean =
        AllowedIPs?.contains("0.0.0.0/0") ?: false && AllowedIPs?.contains("::/0") ?: false

    val isMullvadNode: Boolean
      get() = Name.endsWith(".mullvad.ts.net.")

    val displayName: String
      get() = ComputedName ?: Name

    val exitNodeName: String
      get() {
        if (isMullvadNode &&
                Hostinfo.Location?.Country != null &&
                Hostinfo.Location?.City != null &&
                Hostinfo.Location?.CountryCode != null) {
          return "${Hostinfo.Location!!.CountryCode!!.flag()} ${Hostinfo.Location!!.Country!!}: ${Hostinfo.Location!!.City!!}"
        }
        return displayName
      }

    val keyDoesNotExpire: Boolean
      get() = KeyExpiry == "0001-01-01T00:00:00Z"

    fun isSelfNode(netmap: Netmap.NetworkMap): Boolean = StableID == netmap.SelfNode.StableID

    fun connectedOrSelfNode(nm: Netmap.NetworkMap?) =
        Online == true || StableID == nm?.SelfNode?.StableID

    fun connectedStrRes(nm: Netmap.NetworkMap?) =
        if (connectedOrSelfNode(nm)) R.string.connected else R.string.not_connected

    @Composable
    fun connectedColor(nm: Netmap.NetworkMap?) =
        if (connectedOrSelfNode(nm)) MaterialTheme.colorScheme.on else MaterialTheme.colorScheme.off

    val nameWithoutTrailingDot = Name.trimEnd('.')

    val displayAddresses: List<DisplayAddress>
      get() {
        var addresses = mutableListOf<DisplayAddress>()
        addresses.add(DisplayAddress(nameWithoutTrailingDot))
        Addresses?.let { addresses.addAll(it.map { addr -> DisplayAddress(addr) }) }
        return addresses
      }

    val info: List<PeerSettingInfo>
      get() {
        val result = mutableListOf<PeerSettingInfo>()
        if (Hostinfo.OS?.isNotEmpty() == true) {
          result.add(
              PeerSettingInfo(R.string.os, ComposableStringFormatter(Hostinfo.OS!!)),
          )
        }
        if (keyDoesNotExpire) {
          result.add(
              PeerSettingInfo(
                  R.string.key_expiry, ComposableStringFormatter(R.string.deviceKeyNeverExpires)))
        } else {
          result.add(PeerSettingInfo(R.string.key_expiry, TimeUtil.keyExpiryFromGoTime(KeyExpiry)))
        }
        return result
      }

    @Composable
    fun expiryLabel(): String {
      if (KeyExpiry == GoZeroTimeString) {
        return stringResource(R.string.deviceKeyNeverExpires)
      }

      val expDate = TimeUtil.dateFromGoString(KeyExpiry)
      val template = if (expDate > Date()) R.string.deviceKeyExpires else R.string.deviceKeyExpired
      return stringResource(template, TimeUtil.keyExpiryFromGoTime(KeyExpiry).getString())
    }
  }

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
