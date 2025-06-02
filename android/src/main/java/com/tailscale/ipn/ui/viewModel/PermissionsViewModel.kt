// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import com.tailscale.ipn.TaildropDirectoryStore
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PermissionsViewModel : ViewModel() {
  private val _currentDir =
      MutableStateFlow<String?>(TaildropDirectoryStore.loadSavedDir()?.toString())
  val currentDir: StateFlow<String?> = _currentDir

  fun refreshCurrentDir() {
    val newUri = TaildropDirectoryStore.loadSavedDir()?.toString()
    TSLog.d("PermissionsViewModel", "refreshCurrentDir: $newUri")
    _currentDir.value = newUri
  }
}
