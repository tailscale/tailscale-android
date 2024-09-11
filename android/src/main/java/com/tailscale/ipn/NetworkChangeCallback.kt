// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import libtailscale.Libtailscale
import java.net.InetAddress

object NetworkChangeCallback {

  private const val TAG = "NetworkChangeCallback"

  // Cache LinkProperties and NetworkCapabilities since synchronous ConnectivityManager calls are
  // prone to races.
  // Since there is no guarantee for which update might come first, maybe update DNS configs on
  // both.
  val networkCapabilitiesCache = mutableMapOf<Network, NetworkCapabilities>()
  val linkPropertiesCache = mutableMapOf<Network, LinkProperties>()

  // requestDefaultNetworkCallback receives notifications about the default network. Listen for
  // changes to the capabilities, which are guaranteed to come after a network becomes available per
  // https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback#onAvailable(android.net.Network),
  // in order to filter on non-VPN networks.
  fun monitorDnsChanges(connectivityManager: ConnectivityManager, dns: DnsConfig) {

    connectivityManager.registerDefaultNetworkCallback(
        object : ConnectivityManager.NetworkCallback() {
          override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, capabilities)
            networkCapabilitiesCache[network] = capabilities
            val linkProperties = linkPropertiesCache[network]
            if (linkProperties != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
              maybeUpdateDNSConfig(linkProperties, dns)
            } else {
              if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                  !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                Log.d(
                    TAG,
                    "Capabilities changed for network $network.toString(), but not updating DNS config because either this is a VPN network or non-internet network")
              } else {
                Log.d(
                    TAG,
                    "Capabilities changed for network $network.toString(), but not updating DNS config, because the LinkProperties hasn't been gotten yet")
              }
            }
          }

          override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            super.onLinkPropertiesChanged(network, linkProperties)
            linkPropertiesCache[network] = linkProperties
            val capabilities = networkCapabilitiesCache[network]
            if (capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
              maybeUpdateDNSConfig(linkProperties, dns)
            } else {
              if (capabilities == null) {
                Log.d(
                    TAG,
                    "Capabilities changed for network $network.toString(), but not updating DNS config because capabilities haven't been gotten for this network yet")
              } else {
                Log.d(
                    TAG,
                    "Capabilities changed for network $network.toString(), but not updating DNS config, because this is a VPN network or non-Internet network")
              }
            }
          }

          override fun onLost(network: Network) {
            super.onLost(network)
            if (dns.updateDNSFromNetwork("")) {
              Libtailscale.onDNSConfigChanged("")
            }
          }
        })
  }

  fun maybeUpdateDNSConfig(linkProperties: LinkProperties, dns: DnsConfig) {
    val sb = StringBuilder()
    val dnsList: MutableList<InetAddress> = linkProperties.dnsServers ?: mutableListOf()
    for (ip in dnsList) {
      sb.append(ip.hostAddress).append(" ")
    }
    val searchDomains: String? = linkProperties.domains
    if (searchDomains != null) {
      sb.append("\n")
      sb.append(searchDomains)
    }
    if (dns.updateDNSFromNetwork(sb.toString())) {
      Libtailscale.onDNSConfigChanged(linkProperties.interfaceName)
    }
  }
}
