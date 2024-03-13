// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause


package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.util.defaultPaddingModifier
import com.tailscale.ipn.ui.util.settingsRowModifier
import com.tailscale.ipn.ui.viewModel.Setting
import com.tailscale.ipn.ui.viewModel.SettingType
import com.tailscale.ipn.ui.viewModel.SettingsViewModel


data class SettingsNav(
        val onNavigateToBugReport: () -> Unit,
        val onNavigateToAbout: () -> Unit,
        val onNavigateToMDMSettings: () -> Unit
)

@Composable
fun Settings(viewModel: SettingsViewModel) {
    val handler = LocalUriHandler.current

    Surface(color = MaterialTheme.colorScheme.surface) {

        Column(modifier = defaultPaddingModifier()) {
            viewModel.user?.let { user ->
                UserView(profile = user, viewModel.isAdmin, adminText(), onClick = {
                    handler.openUri(Links.ADMIN_URL)
                })
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { viewModel.ipnActions.logout() }) {
                    Text(text = stringResource(id = R.string.log_out))
                }
            } ?: run {
                Button(onClick = { viewModel.ipnActions.login() }) {
                    Text(text = stringResource(id = R.string.log_in))
                }
            }


            Spacer(modifier = Modifier.height(8.dp))

            viewModel.settings.forEach { settingBundle ->
                Column(modifier = settingsRowModifier()) {
                    settingBundle.title?.let {
                        Text(text = it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
                    }
                    settingBundle.settings.forEach { setting ->
                        when (setting.type) {
                            SettingType.NAV -> {
                                SettingsNavRow(setting)
                            }

                            SettingType.SWITCH -> {
                                SettingsSwitchRow(setting)
                            }

                            SettingType.NAV_WITH_TEXT -> {
                                SettingsNavRow(setting)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun UserView(profile: IpnLocal.LoginProfile?, isAdmin: Boolean, adminText: AnnotatedString, onClick: () -> Unit) {
    Column(modifier = defaultPaddingModifier()) {
        Column(modifier = settingsRowModifier().padding(8.dp)) {
            Text(text = profile?.UserProfile?.DisplayName
                    ?: "", style = MaterialTheme.typography.titleMedium)
            Text(text = profile?.Name ?: "", style = MaterialTheme.typography.bodyMedium)
        }

        if (isAdmin) {
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                ClickableText(text = adminText, style = MaterialTheme.typography.bodySmall, onClick = {
                    onClick()
                })
            }
        }

    }
}

@Composable
fun SettingsNavRow(setting: Setting) {
    val txtVal = setting.value?.collectAsState()?.value ?: ""
    val enabled = setting.enabled.collectAsState().value

    Row(modifier = defaultPaddingModifier().clickable { if (enabled) setting.onClick() }) {
        Text(text = stringResource(id = setting.titleRes))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Text(text = txtVal, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

@Composable
fun SettingsSwitchRow(setting: Setting) {
    val swVal = setting.isOn?.collectAsState()?.value ?: false
    val enabled = setting.enabled.collectAsState().value

    Row(modifier = defaultPaddingModifier().clickable { if (enabled) setting.onClick() }, verticalAlignment = Alignment.CenterVertically) {
        Text(text = stringResource(id = setting.titleRes))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Switch(checked = swVal, onCheckedChange = setting.onToggle, enabled = enabled)
        }
    }
}

@Composable
fun adminText(): AnnotatedString {
    val annotatedString = buildAnnotatedString {
        append(stringResource(id = R.string.settings_admin_prefix))

        pushStringAnnotation(tag = "link", annotation = Links.ADMIN_URL)
        withStyle(style = SpanStyle(color = Color.Blue)) {
            append(stringResource(id = R.string.settings_admin_link))
        }
        pop()
    }
    return annotatedString
}
