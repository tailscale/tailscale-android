// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.dataSource.TailnetLockSetupDataSource
import com.tailscale.ipn.ui.model.IpnState
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

class TailnetLockSetupViewModelFactory(
    private val dataSource: TailnetLockSetupDataSource,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(TailnetLockSetupViewModel::class.java)) {
      return TailnetLockSetupViewModel(dataSource) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}

data class StatusItem(@param:StringRes val title: Int, @param:DrawableRes val icon: Int)

class TailnetLockSetupViewModel(
    private val dataSource: TailnetLockSetupDataSource,
) : ViewModel() {

  private val _statusItems = MutableStateFlow<List<StatusItem>>(emptyList())
  val statusItems: StateFlow<List<StatusItem>> = _statusItems.asStateFlow()

  private val _nodeKey = MutableStateFlow("unknown")
  val nodeKey: StateFlow<String> = _nodeKey.asStateFlow()

  private val _tailnetLockKey = MutableStateFlow("unknown")
  val tailnetLockKey: StateFlow<String> = _tailnetLockKey.asStateFlow()

  suspend fun load() = suspendCancellableCoroutine { continuation ->
    dataSource.loadStatus(viewModelScope) { result ->
      if (!continuation.isActive) return@loadStatus
      _statusItems.value = generateStatusItems(result.getOrNull())
      _nodeKey.value = result.getOrNull()?.NodeKey ?: "unknown"
      _tailnetLockKey.value = result.getOrNull()?.PublicKey ?: "unknown"
      continuation.resume(Unit)
    }
  }

  fun generateStatusItems(networkLockStatus: IpnState.NetworkLockStatus?): List<StatusItem> =
      buildList {
        val status = networkLockStatus ?: return@buildList

        val enabled = status.Enabled == true
        add(
            StatusItem(
                title =
                    if (enabled) R.string.tailnet_lock_enabled else R.string.tailnet_lock_disabled,
                icon = if (enabled) R.drawable.check_circle else R.drawable.xmark_circle,
            )
        )

        val signed = status.NodeKeySigned == true
        add(
            StatusItem(
                title =
                    if (signed) R.string.this_node_has_been_signed
                    else R.string.this_node_has_not_been_signed,
                icon = if (signed) R.drawable.check_circle else R.drawable.xmark_circle,
            )
        )

        val trusted = status.IsPublicKeyTrusted()
        add(
            StatusItem(
                title =
                    if (trusted) R.string.this_node_is_trusted
                    else R.string.this_node_is_not_trusted,
                icon = if (trusted) R.drawable.check_circle else R.drawable.xmark_circle,
            )
        )
      }
}
