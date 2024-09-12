// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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


  // logLinkChanges creates a NetworkCallback to log changes to the network
  // link properties.
  fun logLinkChanges(connectivityManager: ConnectivityManager) {
    connectivityManager.registerDefaultNetworkCallback(defaultLoggingNetworkCallback)

    val networkConnectivityRequest =
      NetworkRequest.Builder()
      .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
      .build()
    connectivityManager.registerNetworkCallback(networkConnectivityRequest, loggingNetworkCallback)
  }

  fun stopLoggingLinkChanges(connectivityManager: ConnectivityManager) {
    connectivityManager.unregisterNetworkCallback(defaultLoggingNetworkCallback)
    connectivityManager.unregisterNetworkCallback(loggingNetworkCallback)
  }

  private val capabilityConsts = mutableMapOf(
      "MMS" to NetworkCapabilities.NET_CAPABILITY_MMS,
      "SUPL" to NetworkCapabilities.NET_CAPABILITY_SUPL,
      "DUN" to NetworkCapabilities.NET_CAPABILITY_DUN,
      "FOTA" to NetworkCapabilities.NET_CAPABILITY_FOTA,
      "IMS" to NetworkCapabilities.NET_CAPABILITY_IMS,
      "WIFI_P2P" to NetworkCapabilities.NET_CAPABILITY_WIFI_P2P,
      "IA" to NetworkCapabilities.NET_CAPABILITY_IA,
      "XCAP" to NetworkCapabilities.NET_CAPABILITY_XCAP,
      "NOT_METERED" to NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
      "INTERNET" to NetworkCapabilities.NET_CAPABILITY_INTERNET,
      "NOT_VPN" to NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
      "TRUSTED" to NetworkCapabilities.NET_CAPABILITY_TRUSTED,
      "TEMP NOT METERED" to NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED,
      "NOT SUSPENDED" to NetworkCapabilities.NET_CAPABILITY_MCX,
      "VALIDATED" to NetworkCapabilities.NET_CAPABILITY_VALIDATED,
      "CAPTIVE PORTAL" to NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL,
  ) as Map<String, Int>

  private val defaultLoggingNetworkCallback = makeNetworkCallback("default: ")
  private val loggingNetworkCallback = makeNetworkCallback("all: ")

  private fun makeNetworkCallback(prefix: String): ConnectivityManager.NetworkCallback {
    return object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        super.onAvailable(network)
        Log.i(TAG, prefix + "network available: ${network.toString()}")
      }

      override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        super.onCapabilitiesChanged(network, capabilities)

        val newCapabilities = capabilityConsts.mapValues {
          capabilities.hasCapability(it.value)
        }

        Log.i(TAG, prefix + "capabilities changed for network ${network.toString()}; new capabilities: ${newCapabilities}")
      }

      override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        super.onLinkPropertiesChanged(network, linkProperties)

        Log.i(TAG, prefix + "link properties changed for network ${network.toString()}; new link properties: ${linkProperties.toString()}")
      }

      override fun onLosing(network: Network, maxMsToLive: Int) {
        super.onLosing(network, maxMsToLive)
        Log.i(TAG, prefix + "losing network: ${network.toString()}; maxMsToLive: ${maxMsToLive}")
      }

      override fun onLost(network: Network) {
        super.onLost(network)
        Log.i(TAG, prefix + "network lost: ${network.toString()}")
      }
    }
  }
}
