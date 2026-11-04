// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.dataSource.RunExitNodeDataSource
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.util.AdvertisedRoutesHelper
import com.tailscale.ipn.util.TSLog
import kotlin.collections.filterNot
import kotlin.collections.orEmpty
import kotlin.collections.toMutableList
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

class RunExitNodeViewModelFactory(
    private val dataSource: RunExitNodeDataSource,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(RunExitNodeViewModel::class.java)) {
      return RunExitNodeViewModel(dataSource) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}

class RunExitNodeViewModel(
    private val dataSource: RunExitNodeDataSource,
) : ViewModel() {
  private val _isRunningExitNode = MutableStateFlow(false)
  val isRunningExitNode: StateFlow<Boolean> = _isRunningExitNode

  private var lastPrefs: Ipn.Prefs? = null

  init {
    viewModelScope.launch {
      dataSource.prefs.collect { prefs ->
        prefs?.let {
          lastPrefs = it
          _isRunningExitNode.value = AdvertisedRoutesHelper.exitNodeOnFromPrefs(it)
        }
      }
    }
  }

  suspend fun setRunningExitNode(isOn: Boolean) {
    val currentPrefs = lastPrefs ?: return
    val prefsOut = if (isOn) setZeroRoutes(currentPrefs) else removeAllZeroRoutes(currentPrefs)

    suspendCancellableCoroutine<Unit> { continuation ->
      dataSource.editPrefs(viewModelScope, prefsOut) { result ->
        TSLog.d("RunExitNodeViewModel", "Edited prefs: $result")
        continuation.resume(Unit)
      }
    }
  }

  private fun setZeroRoutes(prefs: Ipn.Prefs): Ipn.MaskedPrefs {
    val routes = removeAllZeroRoutes(prefs).AdvertiseRoutes.orEmpty().toMutableList()
    routes.add("0.0.0.0/0")
    routes.add("::/0")

    return Ipn.MaskedPrefs().apply { AdvertiseRoutes = routes }
  }

  private fun removeAllZeroRoutes(prefs: Ipn.Prefs): Ipn.MaskedPrefs {
    val routes = prefs.AdvertiseRoutes.orEmpty().filterNot { it == "0.0.0.0/0" || it == "::/0" }

    return Ipn.MaskedPrefs().apply { AdvertiseRoutes = routes }
  }
}
