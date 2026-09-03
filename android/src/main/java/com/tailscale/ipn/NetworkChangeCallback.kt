// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.tailscale.ipn.util.TSLog
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import libtailscale.Libtailscale

internal data class NetworkCandidate<T>(
    val value: T,
    val internet: Boolean,
    val notVpn: Boolean,
    val validated: Boolean,
    val hasDns: Boolean,
    val nonMetered: Boolean,
)

internal fun <T> pickPreferredNetwork(candidates: List<NetworkCandidate<T>>): T? {
  fun pick(requireValidated: Boolean, requireDNS: Boolean): T? {
    val matching =
        candidates.filter {
          it.internet &&
              it.notVpn &&
              (!requireValidated || it.validated) &&
              (!requireDNS || it.hasDns)
        }

    return matching.firstOrNull { it.nonMetered }?.value ?: matching.firstOrNull()?.value
  }

  return pick(requireValidated = true, requireDNS = true)
      ?: pick(requireValidated = true, requireDNS = false)
      ?: pick(requireValidated = false, requireDNS = true)
      ?: pick(requireValidated = false, requireDNS = false)
}

object NetworkChangeCallback {

  private const val TAG = "NetworkChangeCallback"

  private data class NetworkInfo(var caps: NetworkCapabilities, var linkProps: LinkProperties)

  private val lock = ReentrantLock()

  // All currently active non-VPN networks we know about.
  private val activeNetworks = mutableMapOf<Network, NetworkInfo>()

  // Cached chosen default network for outbound sockets.
  @Volatile
  var cachedDefaultNetwork: Network? = null
    private set

  // Cached info for the chosen default network.
  @Volatile private var cachedDefaultNetworkInfo: NetworkInfo? = null

  // Convenience: cached interface name for logging.
  @Volatile
  var cachedDefaultInterfaceName: String? = null
    private set

  @Volatile private var underlyingNetworkListener: ((Network?) -> Unit)? = null

  fun setUnderlyingNetworkListener(listener: ((Network?) -> Unit)?) {
    underlyingNetworkListener = listener
  }

  // monitorDnsChanges sets up a network callback to monitor changes to the
  // system's network state and update the DNS configuration when interfaces
  // become available or properties of those interfaces change.
  fun monitorDnsChanges(connectivityManager: ConnectivityManager, dns: DnsConfig) {
    val networkConnectivityRequest =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

    // Use registerNetworkCallback to listen for updates from all networks, and
    // then update DNS configs for the best network when LinkProperties are changed.
    // Per
    // https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback#onAvailable(android.net.Network), this happens after all other updates.
    //
    // Note that we can't use registerDefaultNetworkCallback because the
    // default network used by Tailscale will always show up with capability
    // NOT_VPN=false, and we must filter out NOT_VPN networks to avoid routing
    // loops.
    connectivityManager.registerNetworkCallback(
        networkConnectivityRequest,
        object : ConnectivityManager.NetworkCallback() {

          override fun onAvailable(network: Network) {
            super.onAvailable(network)

            TSLog.d(TAG, "onAvailable: network $network")

            lock.withLock {
              activeNetworks[network] = NetworkInfo(NetworkCapabilities(), LinkProperties())
              recomputeDefaultNetworkLocked("onAvailable")
            }
          }

          override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, capabilities)

            lock.withLock {
              activeNetworks[network]?.caps = capabilities
              recomputeDefaultNetworkLocked("onCapabilitiesChanged")
            }
          }

          override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            super.onLinkPropertiesChanged(network, linkProperties)

            lock.withLock {
              activeNetworks[network]?.linkProps = linkProperties
              recomputeDefaultNetworkLocked("onLinkPropertiesChanged")
              maybeUpdateDNSConfig("onLinkPropertiesChanged", dns)
            }
          }

          override fun onLost(network: Network) {
            super.onLost(network)

            TSLog.d(TAG, "onLost: network $network")

            lock.withLock {
              activeNetworks.remove(network)
              recomputeDefaultNetworkLocked("onLost")
              maybeUpdateDNSConfig("onLost", dns)
            }
          }
        })
  }

  // pickDefaultNetwork returns a non-VPN network to use as the 'default'
  // network; one that is used as a gateway to the internet and from which we
  // obtain our DNS servers.
  //
  // Networks are preferred in this order:
  //   1. VALIDATED + INTERNET + NOT_VPN + DNS
  //   2. VALIDATED + INTERNET + NOT_VPN
  //   3. INTERNET + NOT_VPN + DNS
  //   4. INTERNET + NOT_VPN
  //   5. null
  //
  // Within each group, prefer a non-metered network. VALIDATED is preferred,
  // but not required, because per
  // https://developer.android.com/develop/connectivity/network-ops/reading-network-state,
  // newly available networks may be usable before Android has finished validating them.
  private fun pickDefaultNetwork(): Network? {
    return pickPreferredNetwork(
        activeNetworks.map { (network, info) ->
          NetworkCandidate(
              value = network,
              internet = info.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
              notVpn = info.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
              validated = info.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
              hasDns = info.linkProps.dnsServers.isNotEmpty(),
              nonMetered = info.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
          )
        })
  }

  // Update cached default network + log interface name.
  private fun recomputeDefaultNetworkLocked(why: String) {
    val oldNetwork = cachedDefaultNetwork
    val newNetwork = pickDefaultNetwork()
    cachedDefaultNetwork = newNetwork

    val info = if (newNetwork != null) activeNetworks[newNetwork] else null
    cachedDefaultNetworkInfo = info
    cachedDefaultInterfaceName = info?.linkProps?.interfaceName

    TSLog.d(
        TAG, "$why: cachedDefaultNetwork=$newNetwork iface=${cachedDefaultInterfaceName ?: "none"}")

    if (newNetwork != oldNetwork) {
      underlyingNetworkListener?.invoke(newNetwork)
    }
  }

  // maybeUpdateDNSConfig will maybe update our DNS configuration based on the
  // current set of active Networks.
  private fun maybeUpdateDNSConfig(why: String, dns: DnsConfig) {
    val defaultNetwork = cachedDefaultNetwork
    if (defaultNetwork == null) {
      TSLog.d(TAG, "$why: no default network available; not updating DNS")
      return
    }

    val info = cachedDefaultNetworkInfo
    if (info == null) {
      Log.w(TAG, "$why: no info for default network; not updating DNS")
      return
    }

    val sb = StringBuilder()
    for (ip in info.linkProps.dnsServers) {
      sb.append(ip.hostAddress).append(" ")
    }

    val searchDomains: String? = info.linkProps.domains
    if (searchDomains != null) {
      sb.append("\n")
      sb.append(searchDomains)
    }

    if (dns.updateDNSFromNetwork(sb.toString())) {
      TSLog.d(TAG, "$why: updated DNS config for iface=${info.linkProps.interfaceName}")

      val gatewayIP =
          info.linkProps.routes
              .filter { it.isDefaultRoute && it.gateway != null }
              .sortedBy { if (it.gateway is java.net.Inet4Address) 0 else 1 }
              .firstNotNullOfOrNull { it.gateway?.hostAddress } ?: ""

      Libtailscale.onGatewayChanged(gatewayIP)
      Libtailscale.onDNSConfigChanged(info.linkProps.interfaceName)
    }
  }
}
