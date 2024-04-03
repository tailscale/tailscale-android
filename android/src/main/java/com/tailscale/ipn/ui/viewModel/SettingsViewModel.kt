// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class SettingType {
  NAV,
  SWITCH,
  TEXT
}

// Represents a UI setting.
// title: The title of the setting
// type: The type of setting
// enabled: Whether the setting is enabled
// value: The value of the setting for textual settings
// isOn: The value of the setting for switch settings
// onClick: The action to take when the setting is clicked (typicall for navigation)
// onToggle: The action to take when the setting is toggled (typically for switches)
//
// Behavior is undefined if you mix the types here. Switch settings should supply an
// isOn and onToggle, while navigation settings should supply an onClick and an optional
// value
data class Setting(
    val titleRes: Int = 0,
    val title: String? = null,
    val type: SettingType,
    val destructive: Boolean = false,
    val enabled: StateFlow<Boolean> = MutableStateFlow(true),
    val isOn: StateFlow<Boolean?>? = null,
    val onClick: (() -> Unit)? = null,
    val onToggle: (Boolean) -> Unit = {}
)

data class SettingsNav(
    val onNavigateToBugReport: () -> Unit,
    val onNavigateToAbout: () -> Unit,
    val onNavigateToDNSSettings: () -> Unit,
    val onNavigateToTailnetLock: () -> Unit,
    val onNavigateToMDMSettings: () -> Unit,
    val onNavigateToManagedBy: () -> Unit,
    val onNavigateToUserSwitcher: () -> Unit,
    val onNavigateToPermissions: () -> Unit,
    val onBackPressed: () -> Unit,
)

class SettingsViewModelFactory(private val navigation: SettingsNav) : ViewModelProvider.Factory {
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return SettingsViewModel(navigation) as T
  }
}

class SettingsViewModel(val navigation: SettingsNav) : IpnViewModel() {
  // Display name for the logged in user
  var isAdmin: StateFlow<Boolean> = MutableStateFlow(false)

  val dns =
      Setting(
          titleRes = R.string.dns_settings,
          type = SettingType.NAV,
          onClick = { navigation.onNavigateToDNSSettings() },
          enabled = MutableStateFlow(true))

  val tailnetLock =
      Setting(
          titleRes = R.string.tailnet_lock,
          type = SettingType.NAV,
          onClick = { navigation.onNavigateToTailnetLock() },
          enabled = MutableStateFlow(true))

  val permissions =
      Setting(
          titleRes = R.string.permissions,
          type = SettingType.NAV,
          onClick = { navigation.onNavigateToPermissions() },
          enabled = MutableStateFlow(true))

  val about =
      Setting(
          titleRes = R.string.about_tailscale,
          type = SettingType.NAV,
          onClick = { navigation.onNavigateToAbout() },
          enabled = MutableStateFlow(true))

  val bugReport =
      Setting(
          titleRes = R.string.bug_report,
          type = SettingType.NAV,
          onClick = { navigation.onNavigateToBugReport() },
          enabled = MutableStateFlow(true))

  val managedBy: StateFlow<Setting?> = MutableStateFlow(null)

  val mdmDebug =
      Setting(
          titleRes = R.string.mdm_settings,
          type = SettingType.NAV,
          onClick = { navigation.onNavigateToMDMSettings() },
          enabled = MutableStateFlow(true))

  init {
    viewModelScope.launch {
      MDMSettings.managedByOrganizationName.flow.collect { managedByOrganization ->
        managedBy.set(
            managedByOrganization?.let {
              Setting(
                  R.string.managed_by_orgName,
                  it,
                  SettingType.NAV,
                  onClick = { navigation.onNavigateToManagedBy() },
                  enabled = MutableStateFlow(true))
            })
      }
    }

    viewModelScope.launch {
      Notifier.netmap.collect { netmap -> isAdmin.set(netmap?.SelfNode?.isAdmin ?: false) }
    }
  }
}
