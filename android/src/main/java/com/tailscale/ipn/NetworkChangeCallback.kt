// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import libtailscale.Libtailscale
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkChangeCallback {

  // requestNetwork attempts to find the best network that matches the passed NetworkRequest. It is
  // possible that this might return an unusuable network, eg a captive portal.
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

            val sb = StringBuilder()
            val linkProperties: LinkProperties? = connectivityManager.getLinkProperties(network)
            val dnsList: MutableList<InetAddress> = linkProperties?.dnsServers ?: mutableListOf()
            for (ip in dnsList) {
              sb.append(ip.hostAddress).append(" ")
            }
            val searchDomains: String? = linkProperties?.domains
            if (searchDomains != null) {
              sb.append("\n")
              sb.append(searchDomains)
            }

            if (dns.updateDNSFromNetwork(sb.toString())) {
              Libtailscale.onDNSConfigChanged(linkProperties?.interfaceName)
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
}
