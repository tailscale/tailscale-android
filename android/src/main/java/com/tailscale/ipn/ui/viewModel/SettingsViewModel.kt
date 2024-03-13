// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.StringSetting
import com.tailscale.ipn.ui.service.IpnManager
import com.tailscale.ipn.ui.service.set
import com.tailscale.ipn.ui.service.toggleCorpDNS
import com.tailscale.ipn.ui.view.SettingsNav
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class SettingType { NAV, SWITCH, NAV_WITH_TEXT }


class ComposableStringFormatter(@StringRes val stringRes: Int, vararg val params: Any) {
    @Composable
    fun getString(): String = stringResource(id = stringRes, *params)
}

// Represents a bundle of settings values that should be grouped together uner a title
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
        val enabled: StateFlow<Boolean> = MutableStateFlow(false),
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
            onToggle = onToggle
    )
}


class SettingsViewModel(
        val ipnManager: IpnManager,
        val navigation: SettingsNav
) : ViewModel() {
    // The logged in user
    val model = ipnManager.model
    val mdmSettings = ipnManager.mdmSettings
    
    val user = model.loggedInUser.value

    // Display name for the logged in user
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
                useDNSSetting.isOn?.set(prefs?.CorpDNS)
                useDNSSetting.enabled.set(prefs != null)
            }
        }
    }

    private val footerSettings: List<Setting> = listOfNotNull(
            Setting(
                    titleRes = R.string.about,
                    SettingType.NAV,
                    onClick = { navigation.onNavigateToAbout() },
                    enabled = MutableStateFlow(true)
            ),
            Setting(
                    titleRes = R.string.bug_report,
                    SettingType.NAV,
                    onClick = { navigation.onNavigateToBugReport() },
                    enabled = MutableStateFlow(true)
            ),
            mdmSettings.get(StringSetting.ManagedByOrganizationName)?.let {
                Setting(
                        ComposableStringFormatter(R.string.managed_by_orgName, it),
                        SettingType.NAV,
                        onClick = { navigation.onNavigateToManagedBy() },
                        enabled = MutableStateFlow(true)
                )
            },
            if (BuildConfig.DEBUG) {
                Setting(
                        titleRes = R.string.mdm_settings,
                        SettingType.NAV,
                        onClick = { navigation.onNavigateToMDMSettings() },
                        enabled = MutableStateFlow(true)
                )
            } else {
                null
            }
    )

    val settings: List<SettingBundle> = listOf(
            SettingBundle(
                    settings = listOf(
                            useDNSSetting,
                    )
            ),
            // General settings, always enabled
            SettingBundle(settings = footerSettings)
    )
}
