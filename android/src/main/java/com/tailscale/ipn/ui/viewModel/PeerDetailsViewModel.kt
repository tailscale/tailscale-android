// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.dataSource.PeerDetailsDataSource
import com.tailscale.ipn.ui.model.Favorites
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.util.ComposableStringFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PeerSettingInfo(val titleRes: Int, val value: ComposableStringFormatter)

class PeerDetailsViewModelFactory(
    private val nodeId: StableNodeID,
    private val pingViewModel: PingViewModel,
    private val dataSource: PeerDetailsDataSource,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(PeerDetailsViewModel::class.java)) {
      return PeerDetailsViewModel(nodeId, pingViewModel, dataSource) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}

class PeerDetailsViewModel(
    val nodeId: StableNodeID,
    val pingViewModel: PingViewModel,
    private val dataSource: PeerDetailsDataSource,
) : ViewModel() {
  val netmap: StateFlow<Netmap.NetworkMap?> = dataSource.netmap
  private val _node = MutableStateFlow<Tailcfg.Node?>(null)
  val node: StateFlow<Tailcfg.Node?> = _node.asStateFlow()
  private val _isPinging = MutableStateFlow(false)
  val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

  val favorites: StateFlow<Favorites?> = dataSource.favorites
  val isWritingFavorites: StateFlow<Boolean> = dataSource.isWritingFavorites

  init {
    viewModelScope.launch {
      dataSource.netmap.collect { netmap ->
        netmap?.getPeer(nodeId)?.let { peer -> _node.value = peer }
      }
    }
  }

  fun startPing() {
    _isPinging.value = true
    node.value?.let { pingViewModel.startPing(it) }
  }

  fun onPingDismissal() {
    _isPinging.value = false
    pingViewModel.handleDismissal()
  }

  fun togglePin() {
    node.value?.let { dataSource.toggleFavorite(it.StableID) }
  }
}
