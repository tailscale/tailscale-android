// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import com.tailscale.ipn.App
import com.tailscale.ipn.NetworkChangeCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WifiAutoConnectViewModel : ViewModel() {
  private val app = App.get()

  private val _whitelistSsids = MutableStateFlow(app.getWhitelistSsids().sorted())
  val whitelistSsids: StateFlow<List<String>> = _whitelistSsids

  private val _blacklistSsids = MutableStateFlow(app.getBlacklistSsids().sorted())
  val blacklistSsids: StateFlow<List<String>> = _blacklistSsids

  private val _defaultOn = MutableStateFlow(app.getWifiAutoConnectDefaultOn())
  val defaultOn: StateFlow<Boolean> = _defaultOn

  fun addToWhitelist(ssid: String) {
    val trimmed = ssid.trim()
    if (trimmed.isEmpty()) return
    mutateSsids(app::getWhitelistSsids, app::setWhitelistSsids, _whitelistSsids) { add(trimmed) }
  }

  fun removeFromWhitelist(ssid: String) =
      mutateSsids(app::getWhitelistSsids, app::setWhitelistSsids, _whitelistSsids) { remove(ssid) }

  fun addToBlacklist(ssid: String) {
    val trimmed = ssid.trim()
    if (trimmed.isEmpty()) return
    mutateSsids(app::getBlacklistSsids, app::setBlacklistSsids, _blacklistSsids) { add(trimmed) }
  }

  fun removeFromBlacklist(ssid: String) =
      mutateSsids(app::getBlacklistSsids, app::setBlacklistSsids, _blacklistSsids) { remove(ssid) }

  private fun mutateSsids(
      get: () -> Set<String>,
      set: (Set<String>) -> Unit,
      flow: MutableStateFlow<List<String>>,
      block: MutableSet<String>.() -> Unit
  ) {
    val updated = get().toMutableSet().apply(block)
    set(updated)
    flow.value = updated.sorted()
    NetworkChangeCallback.checkExistingNetworks()
  }

  fun setDefaultOn(on: Boolean) {
    app.setWifiAutoConnectDefaultOn(on)
    _defaultOn.value = on
    NetworkChangeCallback.checkExistingNetworks()
  }
}
