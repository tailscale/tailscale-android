// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import com.tailscale.ipn.App
import com.tailscale.ipn.autoconnect.TrustedNetworks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TrustedNetworksViewModel(application: Application) : AndroidViewModel(application) {

  private val ctx = application.applicationContext

  private val _enabled = MutableStateFlow(TrustedNetworks.isEnabled(ctx))
  val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

  private val _trustedSsids = MutableStateFlow(TrustedNetworks.load(ctx))
  val trustedSsids: StateFlow<Set<String>> = _trustedSsids.asStateFlow()

  private val _currentSsid = MutableStateFlow<String?>(null)
  val currentSsid: StateFlow<String?> = _currentSsid.asStateFlow()

  init {
    refreshCurrentSsid()
  }

  private fun reevaluate() {
    App.get().networkWatcher.reevaluate()
  }

  fun setEnabled(enabled: Boolean) {
    TrustedNetworks.setEnabled(ctx, enabled)
    _enabled.value = enabled
    reevaluate()
  }

  fun addSsid(ssid: String) {
    TrustedNetworks.add(ctx, ssid)
    _trustedSsids.value = TrustedNetworks.load(ctx)
    reevaluate()
  }

  fun removeSsid(ssid: String) {
    TrustedNetworks.remove(ctx, ssid)
    _trustedSsids.value = TrustedNetworks.load(ctx)
    reevaluate()
  }

  fun refreshCurrentSsid() {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return
    val caps = cm.getNetworkCapabilities(network) ?: return
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
      // Try transportInfo first (works on some Android versions)
      var ssid =
          (caps.transportInfo as? WifiInfo)
              ?.ssid
              ?.trim('"')
              ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

      // Fallback to WifiManager
      if (ssid == null) {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        ssid =
            wm.connectionInfo
                ?.ssid
                ?.trim('"')
                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
      }

      _currentSsid.value = ssid
    }
  }
}
