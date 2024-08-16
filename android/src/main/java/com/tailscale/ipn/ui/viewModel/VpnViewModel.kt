// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class VpnViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(VpnViewModel::class.java)) {
      return VpnViewModel(application) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}

class VpnViewModel(application: Application) : AndroidViewModel(application) {
  // Whether the VPN is prepared
  val _vpnPrepared = MutableStateFlow(false)
  val vpnPrepared: StateFlow<Boolean> = _vpnPrepared

  init {
    prepareVpn()
  }

  private fun prepareVpn() {
    // Check if the user has granted permission yet.
    if (!vpnPrepared.value) {
      val vpnIntent = VpnService.prepare(getApplication())
      if (vpnIntent != null) {
        setVpnPrepared(false)
      } else {
        setVpnPrepared(true)
      }
    }
  }

  fun setVpnPrepared(prepared: Boolean) {
    _vpnPrepared.value = prepared
  }
}
