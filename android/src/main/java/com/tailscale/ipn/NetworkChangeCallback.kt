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
import libtailscale.Libtailscale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object NetworkChangeCallback {

  private const val TAG = "NetworkChangeCallback"

  private data class NetworkInfo(var caps: NetworkCapabilities, var linkProps: LinkProperties)

  private val lock = ReentrantLock()

  private val activeNetworks = mutableMapOf<Network, NetworkInfo>() // keyed by Network

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

            TSLog.d(TAG, "onAvailable: network ${network}")
            lock.withLock {
              activeNetworks[network] = NetworkInfo(NetworkCapabilities(), LinkProperties())
            }
          }

          override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, capabilities)
            lock.withLock { activeNetworks[network]?.caps = capabilities }
          }

          override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            super.onLinkPropertiesChanged(network, linkProperties)
            lock.withLock {
              activeNetworks[network]?.linkProps = linkProperties
              maybeUpdateDNSConfig("onLinkPropertiesChanged", dns)
            }
          }

          override fun onLost(network: Network) {
            super.onLost(network)

            TSLog.d(TAG, "onLost: network ${network}")
            lock.withLock {
              activeNetworks.remove(network)
              maybeUpdateDNSConfig("onLost", dns)
            }
          }
        })
  }

  // pickNonMetered returns the first non-metered network in the list of
  // networks, or the first network if none are non-metered.
  private fun pickNonMetered(networks: Map<Network, NetworkInfo>): Network? {
    for ((network, info) in networks) {
      if (info.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
        return network
      }
    }
    return networks.keys.firstOrNull()
  }

  // pickDefaultNetwork returns a non-VPN network to use as the 'default'
  // network; one that is used as a gateway to the internet and from which we
  // obtain our DNS servers.
  private fun pickDefaultNetwork(): Network? {
    // Filter the list of all networks to those that have the INTERNET
    // capability, are not VPNs, and have a non-zero number of DNS servers
    // available.
    val networks =
        activeNetworks.filter { (_, info) ->
          info.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
              info.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
              info.linkProps.dnsServers.isNotEmpty() == true
        }

    // If we have one; just return it; otherwise, prefer networks that are also
    // not metered (i.e. cell modems).
    val nonMeteredNetwork = pickNonMetered(networks)
    if (nonMeteredNetwork != null) {
      return nonMeteredNetwork
    }

    // Okay, less good; just return the first network that has the INTERNET and
    // NOT_VPN capabilities; even though this interface doesn't have any DNS
    // servers set, we'll use our DNS fallback servers to make queries. It's
    // strictly better to return an interface + use the DNS fallback servers
    // than to return nothing and not be able to route traffic.
    for ((network, info) in activeNetworks) {
      if (info.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
          info.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
        Log.w(
            TAG,
            "no networks available that also have DNS servers set; falling back to first network ${network}")
        return network
      }
    }

    // Otherwise, return nothing; we don't want to return a VPN network since
    // it could result in a routing loop, and a non-INTERNET network isn't
    // helpful.
    Log.w(TAG, "no networks available to pick a default network")
    return null
  }

  // maybeUpdateDNSConfig will maybe update our DNS configuration based on the
  // current set of active Networks.
  private fun maybeUpdateDNSConfig(why: String, dns: DnsConfig) {
    val defaultNetwork = pickDefaultNetwork()
    if (defaultNetwork == null) {
      TSLog.d(TAG, "${why}: no default network available; not updating DNS config")
      return
    }
    val info = activeNetworks[defaultNetwork]
    if (info == null) {
      Log.w(
          TAG,
          "${why}: [unexpected] no info available for default network; not updating DNS config")
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
          "${why}: updated DNS config for network ${defaultNetwork} (${info.linkProps.interfaceName})")
      Libtailscale.onDNSConfigChanged(info.linkProps.interfaceName)
    }
  }
}
