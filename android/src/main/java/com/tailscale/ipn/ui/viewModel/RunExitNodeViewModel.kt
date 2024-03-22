// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RunExitNodeViewModelFactory() : ViewModelProvider.Factory {
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return RunExitNodeViewModel() as T
  }
}

class AdvertisedRoutesHelper() {
  companion object {
    fun exitNodeOnFromPrefs(prefs: Ipn.Prefs): Boolean {
      var v4 = false
      var v6 = false
      prefs.AdvertiseRoutes?.forEach {
        if (it == "0.0.0.0/0") {
          v4 = true
        }
        if (it == "::/0") {
          v6 = true
        }
      }
      return v4 && v6
    }
  }
}

class RunExitNodeViewModel() : IpnViewModel() {

  val isRunningExitNode: StateFlow<Boolean> = MutableStateFlow(false)
  var lastPrefs: Ipn.Prefs? = null

  init {
    viewModelScope.launch {
      Notifier.prefs.stateIn(viewModelScope).collect { prefs ->
        Log.d("RunExitNode", "prefs: AdvertiseRoutes=" + prefs?.AdvertiseRoutes.toString())
        prefs?.let {
          lastPrefs = it
          isRunningExitNode.set(AdvertisedRoutesHelper.exitNodeOnFromPrefs(it))
        } ?: run { isRunningExitNode.set(false) }
      }
    }
  }

  fun setRunningExitNode(isOn: Boolean) {
    LoadingIndicator.start()
    lastPrefs?.let { currentPrefs ->
      val newPrefs: Ipn.MaskedPrefs
      if (isOn) {
        newPrefs = setZeroRoutes(currentPrefs)
      } else {
        newPrefs = removeAllZeroRoutes(currentPrefs)
      }
      Client(viewModelScope).editPrefs(newPrefs) { result ->
        LoadingIndicator.stop()
        Log.d("RunExitNodeViewModel", "Edited prefs: $result")
      }
    }
  }

  private fun setZeroRoutes(prefs: Ipn.Prefs): Ipn.MaskedPrefs {
    val newRoutes = (removeAllZeroRoutes(prefs).AdvertiseRoutes ?: emptyList()).toMutableList()
    newRoutes.add("0.0.0.0/0")
    newRoutes.add("::/0")
    val newPrefs = Ipn.MaskedPrefs()
    newPrefs.AdvertiseRoutes = newRoutes
    return newPrefs
  }

  private fun removeAllZeroRoutes(prefs: Ipn.Prefs): Ipn.MaskedPrefs {
    val newRoutes = emptyList<String>().toMutableList()
    (prefs.AdvertiseRoutes ?: emptyList()).forEach {
      if (it != "0.0.0.0/0" && it != "::/0") {
        newRoutes.add(it)
      }
    }
    val newPrefs = Ipn.MaskedPrefs()
    newPrefs.AdvertiseRoutes = newRoutes
    return newPrefs
  }
}
