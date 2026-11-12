// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.dataSource.SettingsDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class SettingsNav(
    val onNavigateToBugReport: () -> Unit,
    val onNavigateToAbout: () -> Unit,
    val onNavigateToDNSSettings: () -> Unit,
    val onNavigateToSplitTunneling: () -> Unit,
    val onNavigateToTailnetLock: () -> Unit,
    val onNavigateToSubnetRouting: () -> Unit,
    val onNavigateToMDMSettings: () -> Unit,
    val onNavigateToManagedBy: () -> Unit,
    val onNavigateToUserSwitcher: () -> Unit,
    val onNavigateToPermissions: () -> Unit,
    val onNavigateBackHome: () -> Unit,
    val onBackToSettings: () -> Unit,
)

class SettingsViewModelFactory(
    private val dataSource: SettingsDataSource,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
      return SettingsViewModel(dataSource) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}

class SettingsViewModel(
    private val dataSource: SettingsDataSource,
) : ViewModel() {
  // Display name for the logged-in user
  val isAdmin: StateFlow<Boolean> =
      dataSource.netmap
          .map { it?.SelfNode?.isAdmin ?: false }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(5_000),
              false,
          )

  // True if tailnet lock is enabled.  nil if not yet known.
  private val _tailNetLockEnabled = MutableStateFlow<Boolean?>(null)
  val tailNetLockEnabled: StateFlow<Boolean?> = _tailNetLockEnabled.asStateFlow()

  // True if tailscaleDNS is enabled. nil if not yet known.
  val corpDNSEnabled: StateFlow<Boolean?> =
      dataSource.prefs
          .map { it?.CorpDNS }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(5_000),
              null,
          )

  private val _isClientRemoteLoggingEnabled = MutableStateFlow(true)
  val isClientRemoteLoggingEnabled: StateFlow<Boolean> = _isClientRemoteLoggingEnabled.asStateFlow()

  init {
    _isClientRemoteLoggingEnabled.value = dataSource.isClientLoggingEnabled()

    dataSource.tailnetLockStatus(viewModelScope) { result ->
      result.onSuccess { status -> _tailNetLockEnabled.value = status.Enabled }
    }
  }

  fun toggleIsClientRemoteLoggingEnabled() {
    val enabled = !isClientRemoteLoggingEnabled.value
    _isClientRemoteLoggingEnabled.value = enabled
    dataSource.setClientLoggingEnabled(enabled)
  }
}
