// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.viewModel

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.ViewModel
import com.tailscale.ipn.ui.service.IpnActions
import com.tailscale.ipn.ui.service.IpnModel
import com.tailscale.ipn.ui.view.SettingsNav

enum class SettingType { NAV, SWITCH, NAV_WITH_TEXT }

data class Setting(
        val title: String,
        val type: SettingType,
        val value: String = "",
        val isOn: Boolean = false,
        val onClick: () -> Unit = {})

data class SettingBundle(val title: String? = null, val settings: List<Setting>)

class SettingsViewModel(val model: IpnModel, val ipnActions: IpnActions, val navigation: SettingsNav): ViewModel() {
    // The logged in user
    val user = model.loggedInUser.value

    // Display name for the logged in user
    val userName = user?.UserProfile?.DisplayName ?: ""
    val tailnetName = user?.Name ?: ""
    val isAdmin = model.netmap.value?.SelfNode?.isAdmin ?: false

    val settings: List<SettingBundle> = listOf(
            SettingBundle(settings = listOf(
                    Setting("Use Tailscale DNS", SettingType.SWITCH),
            )),
            SettingBundle(settings = listOf(
                Setting("About", SettingType.NAV, onClick = { navigation.onNavigateToAbout()}),
                Setting("Bug Report", SettingType.NAV, onClick = {  navigation.onNavigateToBugReport()})
            ))
    )

    fun adminText(): AnnotatedString {
        val annotatedString = buildAnnotatedString {
            append("You can manage your account from the admin console. ")

            pushStringAnnotation(tag = "policy", annotation = "https://google.com/policy")
            withStyle(style = SpanStyle(color = Color.Blue)) {
                append("View admin console...")
            }
            pop()
        }
        return annotatedString
    }


}