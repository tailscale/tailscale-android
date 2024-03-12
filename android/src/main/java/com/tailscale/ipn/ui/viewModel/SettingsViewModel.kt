// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.service.IpnActions
import com.tailscale.ipn.ui.service.IpnModel
import com.tailscale.ipn.ui.service.toggleCorpDNS
import com.tailscale.ipn.ui.view.SettingsNav
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class SettingType { NAV, SWITCH, NAV_WITH_TEXT }

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
        val titleRes: Int,
        val type: SettingType,
        val enabled: MutableStateFlow<Boolean> = MutableStateFlow(false),
        val value: MutableStateFlow<String?>? = null,
        val isOn: MutableStateFlow<Boolean?>? = null,
        val onClick: () -> Unit = {},
        val onToggle: (Boolean) -> Unit = {})

data class SettingBundle(val title: String? = null, val settings: List<Setting>)

class SettingsViewModel(val model: IpnModel, val ipnActions: IpnActions, val navigation: SettingsNav) : ViewModel() {
    // The logged in user
    val user = model.loggedInUser.value

    // Display name for the logged in user
    val userName = user?.UserProfile?.DisplayName ?: ""
    val tailnetName = user?.Name ?: ""
    val isAdmin = model.netmap.value?.SelfNode?.isAdmin ?: false

    val useDNSSetting = Setting(
            R.string.use_ts_dns,
            SettingType.SWITCH,
            isOn = MutableStateFlow(model.prefs.value?.CorpDNS),
            onToggle = {
                model.toggleCorpDNS {
                    // (jonathan) TODO: Error handling
                }
            })

    init {
        viewModelScope.launch {
            // Monitor our prefs for changes and update the displayed values accordingly
            model.prefs.collect { prefs ->
                useDNSSetting.isOn?.value = prefs?.CorpDNS
                useDNSSetting.enabled?.value = prefs != null
            }
        }
    }

    val settings: List<SettingBundle> = listOf(
            SettingBundle(settings = listOf(
                    useDNSSetting,
            )),
            // General settings, always enabled
            SettingBundle(settings = listOf(
                    Setting(R.string.about, SettingType.NAV, onClick = { navigation.onNavigateToAbout() }, enabled = MutableStateFlow(true)),
                    Setting(R.string.bug_report, SettingType.NAV, onClick = { navigation.onNavigateToBugReport() }, enabled = MutableStateFlow(true)),
                    Setting(R.string.mdm_settings, SettingType.NAV, onClick = { navigation.onNavigateToMDMSettings() }, enabled = MutableStateFlow(true))
            ))
    )
}
