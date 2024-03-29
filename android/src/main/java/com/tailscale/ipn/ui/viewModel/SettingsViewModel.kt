// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.StringSetting
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

class ComposableStringFormatter(@StringRes val stringRes: Int, vararg val params: Any) {
  @Composable fun getString(): String = stringResource(id = stringRes, *params)
}

// Represents a bundle of settings values that should be grouped together under a title
data class SettingBundle(val title: String? = null, val settings: List<Setting>)

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
    val title: ComposableStringFormatter,
    val type: SettingType,
    val destructive: Boolean = false,
    val enabled: StateFlow<Boolean> = MutableStateFlow(true),
    val value: StateFlow<String?>? = null,
    val isOn: StateFlow<Boolean?>? = null,
    val onClick: () -> Unit = {},
    val onToggle: (Boolean) -> Unit = {}
) {
  constructor(
      titleRes: Int,
      type: SettingType,
      enabled: StateFlow<Boolean> = MutableStateFlow(false),
      value: StateFlow<String?>? = null,
      isOn: StateFlow<Boolean?>? = null,
      onClick: () -> Unit = {},
      onToggle: (Boolean) -> Unit = {}
  ) : this(
      title = ComposableStringFormatter(titleRes),
      type = type,
      enabled = enabled,
      value = value,
      isOn = isOn,
      onClick = onClick,
      onToggle = onToggle)
}

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

  val settings: StateFlow<List<SettingBundle>> = MutableStateFlow(emptyList())

  init {
    viewModelScope.launch {
      mdmSettings.collect { mdmSettings ->
        settings.set(
            listOf(
                // Empty for now
                SettingBundle(settings = listOf()),
                // General settings, always enabled
                SettingBundle(settings = footerSettings(mdmSettings))))
      }
    }

    viewModelScope.launch {
      Notifier.netmap.collect { netmap -> isAdmin.set(netmap?.SelfNode?.isAdmin ?: false) }
    }
  }

  private fun footerSettings(mdmSettings: MDMSettings): List<Setting> =
      listOfNotNull(
          Setting(
              titleRes = R.string.dns_settings,
              SettingType.NAV,
              onClick = { navigation.onNavigateToDNSSettings() },
              enabled = MutableStateFlow(true)),
          Setting(
              titleRes = R.string.tailnet_lock,
              SettingType.NAV,
              onClick = { navigation.onNavigateToTailnetLock() },
              enabled = MutableStateFlow(true)),
          Setting(
              titleRes = R.string.permissions,
              SettingType.NAV,
              onClick = { navigation.onNavigateToPermissions() },
              enabled = MutableStateFlow(true)),
          Setting(
              titleRes = R.string.about,
              SettingType.NAV,
              onClick = { navigation.onNavigateToAbout() },
              enabled = MutableStateFlow(true)),
          Setting(
              titleRes = R.string.bug_report,
              SettingType.NAV,
              onClick = { navigation.onNavigateToBugReport() },
              enabled = MutableStateFlow(true)),
          mdmSettings.get(StringSetting.ManagedByOrganizationName)?.let {
            Setting(
                ComposableStringFormatter(R.string.managed_by_orgName, it),
                SettingType.NAV,
                onClick = { navigation.onNavigateToManagedBy() },
                enabled = MutableStateFlow(true))
          },
          if (BuildConfig.DEBUG) {
            Setting(
                titleRes = R.string.mdm_settings,
                SettingType.NAV,
                onClick = { navigation.onNavigateToMDMSettings() },
                enabled = MutableStateFlow(true))
          } else {
            null
          })
}
