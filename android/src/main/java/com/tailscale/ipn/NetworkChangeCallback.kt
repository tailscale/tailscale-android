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

object NetworkChangeCallback {

  private const val TAG = "NetworkChangeCallback"

  private data class NetworkInfo(
      var caps: NetworkCapabilities,
      var linkProps: LinkProperties
  )

  private val lock = ReentrantLock()

  // All currently active non-VPN networks we know about.
  private val activeNetworks = mutableMapOf<Network, NetworkInfo>()

  /**
   * Cached chosen default network for outbound sockets.
   *
   * Volatile so readers outside the lock can use it safely.
   */
  @Volatile
  var cachedDefaultNetwork: Network? = null
    private set

  /**
   * Cached info for the chosen default network.
   * Persisted for stable logging even if activeNetworks changes.
   */
  @Volatile
  var cachedDefaultNetworkInfo: NetworkInfo? = null
    private set

  /**
   * Convenience: cached interface name for logging.
   */
  @Volatile
  var cachedDefaultInterfaceName: String? = null
    private set

  /**
   * monitorDnsChanges registers callbacks to watch all non-VPN networks and
   * recompute our preferred default network + DNS when things change.
   */
  fun monitorDnsChanges(connectivityManager: ConnectivityManager, dns: DnsConfig) {
    val networkConnectivityRequest =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

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

  /**
   * Prefer non-metered network if available.
   */
  private fun pickNonMetered(networks: Map<Network, NetworkInfo>): Network? {
    for ((network, info) in networks) {
      if (info.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
        return network
      }
    }
    return networks.keys.firstOrNull()
  }

  /**
   * Choose the best non-VPN network to use for outbound sockets.
   */
  private fun pickDefaultNetwork(): Network? {
    val networks =
        activeNetworks.filter { (_, info) ->
          info.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
              info.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
              info.linkProps.dnsServers.isNotEmpty()
        }

    val nonMetered = pickNonMetered(networks)
    if (nonMetered != null) {
      return nonMetered
    }

    // Fallback: any INTERNET + NOT_VPN network even without DNS
    for ((network, info) in activeNetworks) {
      if (info.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
          info.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
        Log.w(
            TAG,
            "no networks with DNS; falling back to first INTERNET network $network")
        return network
      }
    }

    Log.w(TAG, "no networks available to pick default network")
    return null
  }

  /**
   * Update cached default network + log interface name.
   */
  private fun recomputeDefaultNetworkLocked(why: String) {
    val newNetwork = pickDefaultNetwork()
    cachedDefaultNetwork = newNetwork

    val info = if (newNetwork != null) activeNetworks[newNetwork] else null
    cachedDefaultNetworkInfo = info
    cachedDefaultInterfaceName = info?.linkProps?.interfaceName

    TSLog.d(
        TAG,
        "$why: cachedDefaultNetwork=$newNetwork iface=${cachedDefaultInterfaceName ?: "none"}")
  }

  /**
   * Update DNS config when underlying network changes.
   */
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
      TSLog.d(
          TAG,
          "$why: updated DNS config for iface=${info.linkProps.interfaceName}")
      Libtailscale.onDNSConfigChanged(info.linkProps.interfaceName)
    }
  }
}
