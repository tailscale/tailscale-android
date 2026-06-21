// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
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
    val matching = candidates.filter {
      it.internet && it.notVpn && (!requireValidated || it.validated) && (!requireDNS || it.hasDns)
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

  // Networks where we auto-started VPN (blacklist or defaultOn). Tracked to auto-stop on leave.
  private val startedForNetworks = mutableMapOf<Network, String>()

  // Networks where we auto-stopped VPN (whitelist). Tracked to restart VPN on leave if defaultOn.
  private val stoppedForNetworks = mutableMapOf<Network, String>()

  // User manually stopped VPN on a blacklist/defaultOn network → suppress re-auto-start.
  private val userStoppedOnNetworks = mutableSetOf<Network>()

  // User manually started VPN on a whitelist network → suppress auto-stop.
  private val userStartedOnWhitelist = mutableSetOf<Network>()

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

          override fun onCapabilitiesChanged(
              network: Network,
              capabilities: NetworkCapabilities,
          ) {
            super.onCapabilitiesChanged(network, capabilities)

            lock.withLock {
              activeNetworks[network]?.caps = capabilities

              if (recomputeDefaultNetworkLocked("onCapabilitiesChanged")) {
                maybeUpdateDNSConfig("onCapabilitiesChanged", dns)
              }
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
        },
    )
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
        }
    )
  }

  // Update cached default network + log interface name. Return whether or not default network
  // changed.
  private fun recomputeDefaultNetworkLocked(why: String): Boolean {
    val oldNetwork = cachedDefaultNetwork
    val newNetwork = pickDefaultNetwork()

    cachedDefaultNetwork = newNetwork

    val info = if (newNetwork != null) activeNetworks[newNetwork] else null
    cachedDefaultNetworkInfo = info
    cachedDefaultInterfaceName = info?.linkProps?.interfaceName

    TSLog.d(
        TAG,
        "$why: cachedDefaultNetwork=$newNetwork iface=${cachedDefaultInterfaceName ?: "none"}",
    )

    if (newNetwork != oldNetwork) {
      underlyingNetworkListener?.invoke(newNetwork)
      return true
    }

    return false
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

  // userStoppedVpn: user manually stopped VPN → suppress re-auto-start on current networks.
  fun userStoppedVpn() {
    lock.withLock { userStoppedOnNetworks.addAll(startedForNetworks.keys) }
  }

  // userStartedVpn: user manually started VPN → suppress auto-stop on whitelist networks.
  fun userStartedVpn() {
    lock.withLock { userStartedOnWhitelist.addAll(stoppedForNetworks.keys) }
  }

  // monitorWifiAutoConnect registers a callback to auto-start the VPN on trusted SSIDs and
  // auto-stop it when leaving those networks.
  fun monitorWifiAutoConnect(connectivityManager: ConnectivityManager, app: UninitializedApp) {
    val request =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

    connectivityManager.registerNetworkCallback(
        request,
        object : ConnectivityManager.NetworkCallback() {
          override fun onCapabilitiesChanged(
              network: Network,
              capabilities: NetworkCapabilities,
          ) {
            super.onCapabilitiesChanged(network, capabilities)
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
            val ssid = getSsidFromCaps(capabilities, app) ?: return
            val action = lock.withLock { evaluate(app, network, ssid) }
            action?.invoke()
          }

          override fun onLost(network: Network) {
            super.onLost(network)
            var action: (() -> Unit)? = null
            lock.withLock {
              val startedSsid = startedForNetworks.remove(network)
              val stoppedSsid = stoppedForNetworks.remove(network)
              userStoppedOnNetworks.remove(network)
              userStartedOnWhitelist.remove(network)
              if (startedSsid != null) {
                TSLog.d(TAG, "wifi: left blacklist/defaultOn network $startedSsid → stopping VPN")
                action = { stopVpnNow(app) }
              } else if (stoppedSsid != null && app.getWifiAutoConnectDefaultOn()) {
                TSLog.d(
                    TAG,
                    "wifi: left whitelist network $stoppedSsid, defaultOn=true → starting VPN",
                )
                action = { enqueueVpnStart(app) }
              }
            }
            action?.invoke()
          }
        },
    )
  }

  // evaluate decides the auto-connect action for a WiFi network. Must be called under lock.
  // Returns the action to run outside the lock, or null if no action is needed (already
  // handled, suppressed by a manual override, or an unknown network with defaultOn off).
  private fun evaluate(app: UninitializedApp, network: Network, ssid: String): (() -> Unit)? {
    val whitelist = app.getWhitelistSsids()
    val blacklist = app.getBlacklistSsids()
    val defaultOn = app.getWifiAutoConnectDefaultOn()
    val isWhitelisted = whitelist.contains(ssid)
    val isBlacklisted = blacklist.contains(ssid)
    TSLog.d(TAG, "wifi: ssid=$ssid whitelist=$whitelist blacklist=$blacklist defaultOn=$defaultOn")
    when {
      isWhitelisted -> {
        if (network in userStartedOnWhitelist) return null
        if (network in stoppedForNetworks) return null
        TSLog.d(TAG, "wifi: whitelist → stopping VPN")
        stoppedForNetworks[network] = ssid
        return { stopVpnNow(app) }
      }
      isBlacklisted || defaultOn -> {
        if (network in userStoppedOnNetworks) return null
        if (network in startedForNetworks) return null
        startedForNetworks[network] = ssid
        val reason = if (isBlacklisted) "blacklist" else "defaultOn"
        if (!app.isAbleToStartVPN()) {
          TSLog.d(TAG, "wifi: $reason → not yet authenticated, tracking network")
          return null
        }
        TSLog.d(TAG, "wifi: $reason → starting VPN")
        return { enqueueVpnStart(app) }
      }
      else -> TSLog.d(TAG, "wifi: unknown network, defaultOn=false → no action")
    }
    return null
  }

  // checkExistingNetworks re-evaluates all active WiFi networks against current lists.
  // Call when whitelist, blacklist, or defaultOn setting changes.
  fun checkExistingNetworks() {
    val app = App.get()
    val actions = mutableListOf<() -> Unit>()
    lock.withLock {
      val whitelist = app.getWhitelistSsids()
      val blacklist = app.getBlacklistSsids()
      val defaultOn = app.getWifiAutoConnectDefaultOn()
      // Clean stale entries for SSIDs removed from their respective lists.
      if (!defaultOn) startedForNetworks.entries.removeIf { (_, ssid) -> !blacklist.contains(ssid) }
      stoppedForNetworks.entries.removeIf { (_, ssid) -> !whitelist.contains(ssid) }
      for ((network, info) in activeNetworks) {
        if (!info.caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
        val ssid = getSsidFromCaps(info.caps, app) ?: continue
        evaluate(app, network, ssid)?.let { actions += it }
      }
    }
    actions.forEach { it() }
  }

  // cleanSsid strips the quotes WifiManager puts around SSIDs and filters placeholders.
  fun cleanSsid(raw: String?): String? =
      raw?.removePrefix("\"")?.removeSuffix("\"")?.takeIf {
        it.isNotEmpty() && it != "<unknown ssid>"
      }

  private fun getSsidFromCaps(capabilities: NetworkCapabilities, app: UninitializedApp): String? {
    // Try transportInfo first (preferred on API 29+, no location perm needed on API 31+).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val fromTransport = cleanSsid((capabilities.transportInfo as? WifiInfo)?.ssid)
      if (fromTransport != null) return fromTransport
    }
    // Fallback: WifiManager (works if ACCESS_FINE_LOCATION or NEARBY_WIFI_DEVICES is granted).
    @Suppress("DEPRECATION")
    return cleanSsid(
        (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.ssid
    )
  }

  private fun enqueueVpnStart(app: UninitializedApp) {
    val req = OneTimeWorkRequest.Builder(StartVPNWorker::class.java).build()
    WorkManager.getInstance(app)
        .enqueueUniqueWork("wifi_auto_connect", ExistingWorkPolicy.KEEP, req)
  }

  private fun stopVpnNow(app: UninitializedApp) {
    WorkManager.getInstance(app).cancelUniqueWork("wifi_auto_connect")
    app.stopVPN()
  }
}
