// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.ComposableStringFormatter
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class PeerSettingInfo(val titleRes: Int, val value: ComposableStringFormatter)

class PeerDetailsViewModelFactory(private val nodeId: StableNodeID, private val filesDir: File) :
    ViewModelProvider.Factory {
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return PeerDetailsViewModel(nodeId, filesDir) as T
  }
}

class PeerDetailsViewModel(val nodeId: StableNodeID, val filesDir: File) : IpnViewModel() {
  val node: StateFlow<Tailcfg.Node?> = MutableStateFlow(null)

  init {
    viewModelScope.launch {
      Notifier.netmap.collect { nm ->
        netmap.set(nm)
        nm?.getPeer(nodeId)?.let { peer -> node.set(peer) }
      }
    }
  }
}
