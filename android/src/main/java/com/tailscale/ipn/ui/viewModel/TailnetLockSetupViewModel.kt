// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.IpnState
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TailnetLockSetupViewModelFactory : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return TailnetLockSetupViewModel() as T
  }
}

data class StatusItem(@StringRes val title: Int, @DrawableRes val icon: Int)

class TailnetLockSetupViewModel : IpnViewModel() {

  val statusItems: StateFlow<List<StatusItem>> = MutableStateFlow(emptyList())
  val nodeKey: StateFlow<String> = MutableStateFlow("unknown")
  val tailnetLockKey: StateFlow<String> = MutableStateFlow("unknown")

  init {
    LoadingIndicator.start()
    Client(viewModelScope).tailnetLockStatus { result ->
      statusItems.set(generateStatusItems(result.getOrNull()))
      nodeKey.set(result.getOrNull()?.NodeKey ?: "unknown")
      tailnetLockKey.set(result.getOrNull()?.PublicKey ?: "unknown")
      LoadingIndicator.stop()
    }
  }

  fun generateStatusItems(networkLockStatus: IpnState.NetworkLockStatus?): List<StatusItem> {
    networkLockStatus?.let { status ->
      val items = emptyList<StatusItem>().toMutableList()
      if (status.Enabled == true) {
        items.add(StatusItem(title = R.string.tailnet_lock_enabled, icon = R.drawable.check_circle))
      } else {
        items.add(
            StatusItem(title = R.string.tailnet_lock_disabled, icon = R.drawable.xmark_circle))
      }

      if (status.NodeKeySigned == true) {
        items.add(
            StatusItem(title = R.string.this_node_has_been_signed, icon = R.drawable.check_circle))
      } else {
        items.add(
            StatusItem(
                title = R.string.this_node_has_not_been_signed, icon = R.drawable.xmark_circle))
      }

      if (status.IsPublicKeyTrusted()) {
        items.add(StatusItem(title = R.string.this_node_is_trusted, icon = R.drawable.check_circle))
      } else {
        items.add(
            StatusItem(title = R.string.this_node_is_not_trusted, icon = R.drawable.xmark_circle))
      }

      return items
    }
        ?: run {
          return emptyList()
        }
  }
}
