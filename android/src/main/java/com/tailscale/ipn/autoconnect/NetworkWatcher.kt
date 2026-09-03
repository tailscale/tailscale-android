// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.autoconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog

class NetworkWatcher(private val context: Context) {

  private val cm =
      context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  private var registered = false
  private val handler = Handler(Looper.getMainLooper())
  private var pendingEvaluation: Runnable? = null
  private var lastAction: String? = null
  private var ssidRetryCount = 0

  fun register() {
    if (registered) return
    val request =
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
    cm.registerNetworkCallback(request, callback)
    registered = true
    evaluateCurrent()
  }

  fun unregister() {
    if (!registered) return
    cm.unregisterNetworkCallback(callback)
    registered = false
  }

  private fun scheduleEvaluation() {
    pendingEvaluation?.let { handler.removeCallbacks(it) }
    val runnable = Runnable { evaluateCurrent() }
    pendingEvaluation = runnable
    handler.postDelayed(runnable, DEBOUNCE_MS)
  }

  private val callback =
      object : ConnectivityManager.NetworkCallback() {

        override fun onCapabilitiesChanged(
            network: Network,
            caps: NetworkCapabilities
        ) {
          if (!TrustedNetworks.isEnabled(context)) return
          // Ignore VPN network changes — they are created by us
          if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
          scheduleEvaluation()
        }

        override fun onLost(network: Network) {
          if (!TrustedNetworks.isEnabled(context)) return
          scheduleEvaluation()
        }
      }

  private fun getSsid(caps: NetworkCapabilities): String? {
    // Method 1: transportInfo from NetworkCapabilities
    var ssid =
        (caps.transportInfo as? WifiInfo)?.ssid?.trim('"')
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    if (ssid != null) {
      TSLog.d(TAG, "SSID from transportInfo: $ssid")
      return ssid
    }

    // Method 2: WifiManager.connectionInfo (deprecated but works on some Android 12+ devices)
    val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    @Suppress("DEPRECATION")
    ssid = wm.connectionInfo?.ssid?.trim('"')
        ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    if (ssid != null) {
      TSLog.d(TAG, "SSID from WifiManager: $ssid")
      return ssid
    }

    // Method 3: WifiManager.scanResults — match by BSSID from connectionInfo
    @Suppress("DEPRECATION")
    val bssid = wm.connectionInfo?.bssid
    if (bssid != null) {
      ssid = wm.scanResults
          ?.firstOrNull { it.BSSID == bssid }
          ?.SSID
          ?.trim('"')
          ?.takeIf { it.isNotBlank() }
      if (ssid != null) {
        TSLog.d(TAG, "SSID from scanResults: $ssid")
        return ssid
      }
    }

    TSLog.d(TAG, "Could not determine SSID")
    return null
  }

  private fun handleWifi(caps: NetworkCapabilities) {
    val ssid = getSsid(caps)

    if (ssid == null) {
      // SSID may not be available yet after Wi-Fi association — retry a few times
      if (ssidRetryCount < MAX_SSID_RETRIES) {
        ssidRetryCount++
        TSLog.d(TAG, "SSID null on Wi-Fi, scheduling retry $ssidRetryCount/$MAX_SSID_RETRIES")
        handler.postDelayed({ evaluateCurrent() }, SSID_RETRY_DELAY_MS)
      }
      return
    }

    ssidRetryCount = 0
    val trusted = TrustedNetworks.load(context)
    TSLog.d(TAG, "SSID='$ssid' trusted_list=$trusted match=${ssid in trusted}")
    if (ssid in trusted) {
      disableVpn()
    } else {
      enableVpn()
    }
  }

  private fun enableVpn() {
    if (lastAction == "enable") return
    val state = Notifier.state.value
    if (state == Ipn.State.Running) {
      lastAction = "enable"
      return
    }
    lastAction = "enable"
    TSLog.d(TAG, "Enabling VPN (auto-vpn)")
    App.get().startVPN()
  }

  private fun disableVpn() {
    if (lastAction == "disable") return
    val state = Notifier.state.value
    if (state != Ipn.State.Running) {
      lastAction = "disable"
      return
    }
    lastAction = "disable"
    TSLog.d(TAG, "Disabling VPN (trusted network)")
    App.get().stopVPN()
  }

  fun reevaluate() {
    lastAction = null
    ssidRetryCount = 0
    evaluateCurrent()
  }

  fun evaluateCurrent() {
    if (!TrustedNetworks.isEnabled(context)) return

    // Find the underlying Wi-Fi or cellular network, skipping VPN
    var wifiCaps: NetworkCapabilities? = null
    var hasCellular = false

    for (network in cm.allNetworks) {
      val caps = cm.getNetworkCapabilities(network) ?: continue
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        wifiCaps = caps
        break // Wi-Fi takes priority
      }
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
        hasCellular = true
      }
    }

    TSLog.d(TAG, "evaluateCurrent: wifi=${wifiCaps != null} cellular=$hasCellular")

    when {
      wifiCaps != null -> handleWifi(wifiCaps)
      hasCellular -> enableVpn()
      else -> enableVpn()
    }
  }

  companion object {
    private const val TAG = "NetworkWatcher"
    private const val DEBOUNCE_MS = 2000L
    private const val SSID_RETRY_DELAY_MS = 3000L
    private const val MAX_SSID_RETRIES = 5
  }
}
