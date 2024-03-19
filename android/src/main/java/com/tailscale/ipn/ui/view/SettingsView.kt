// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause


package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.theme.ts_color_dark_desctrutive_text
import com.tailscale.ipn.ui.util.defaultPaddingModifier
import com.tailscale.ipn.ui.util.settingsRowModifier
import com.tailscale.ipn.ui.viewModel.Setting
import com.tailscale.ipn.ui.viewModel.SettingType
import com.tailscale.ipn.ui.viewModel.SettingsNav
import com.tailscale.ipn.ui.viewModel.SettingsViewModel
import com.tailscale.ipn.ui.viewModel.SettingsViewModelFactory


@Composable
fun Settings(
        settingsNav: SettingsNav,
        viewModel: SettingsViewModel = viewModel(factory = SettingsViewModelFactory(settingsNav))
) {
    val handler = LocalUriHandler.current
    val user = viewModel.loggedInUser.collectAsState().value
    val isAdmin = viewModel.isAdmin.collectAsState().value

    Scaffold(topBar = {
        Header(title = R.string.settings_title)
    }) { innerPadding ->

        Column(modifier = Modifier
                .padding(innerPadding)
                .fillMaxHeight()) {

            UserView(profile = user,
                    actionState = UserActionState.NAV,
                    onClick = viewModel.navigation.onNavigateToUserSwitcher)
            if (isAdmin) {
                Spacer(modifier = Modifier.height(4.dp))
                AdminTextView { handler.openUri(Links.ADMIN_URL) }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val settings = viewModel.settings.collectAsState().value
            settings.forEach { settingBundle ->
                Column(modifier = settingsRowModifier()) {
                    settingBundle.title?.let {
                        SettingTitle(it)
                    }
                    settingBundle.settings.forEach { SettingRow(it) }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}


@Composable
fun UserView(
        profile: IpnLocal.LoginProfile?,
        isAdmin: Boolean,
        adminText: AnnotatedString,
        onClick: () -> Unit
) {
    Column {
        Row(modifier = settingsRowModifier().padding(8.dp)) {

            Box(modifier = defaultPaddingModifier()) {
                Avatar(profile = profile, size = 36)
            }

            Column(verticalArrangement = Arrangement.Center) {
                Text(
                        text = profile?.UserProfile?.DisplayName ?: "",
                        style = MaterialTheme.typography.titleMedium
                )
                Text(text = profile?.Name ?: "", style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (isAdmin) {
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                ClickableText(
                        text = adminText,
                        style = MaterialTheme.typography.bodySmall,
                        onClick = {
                            onClick()
                        })
            }
        }

    }
}

@Composable
fun SettingTitle(title: String) {
    Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(8.dp)
    )
}

@Composable
fun SettingRow(setting: Setting) {
    val enabled = setting.enabled.collectAsState().value
    val swVal = setting.isOn?.collectAsState()?.value ?: false
    val txtVal = setting.value?.collectAsState()?.value ?: ""

    Row(modifier = defaultPaddingModifier().clickable { if (enabled) setting.onClick() }, verticalAlignment = Alignment.CenterVertically) {
        when (setting.type) {
            SettingType.NAV_WITH_TEXT -> {
                Text(setting.title.getString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (setting.destructive) ts_color_dark_desctrutive_text else MaterialTheme.colorScheme.primary)
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Text(text = txtVal, style = MaterialTheme.typography.bodyMedium)
                }

            }

            SettingType.TEXT -> {
                Text(setting.title.getString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (setting.destructive) ts_color_dark_desctrutive_text else MaterialTheme.colorScheme.primary)
            }

            SettingType.SWITCH -> {
                Text(setting.title.getString())
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Switch(checked = swVal, onCheckedChange = setting.onToggle, enabled = enabled)
                }
            }

            SettingType.NAV -> {
                Text(setting.title.getString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (setting.destructive) ts_color_dark_desctrutive_text else MaterialTheme.colorScheme.primary)
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Text(text = txtVal, style = MaterialTheme.typography.bodyMedium)
                }
                ChevronRight()
            }
        }
    }
}

@Composable
fun AdminTextView(onNavigateToAdminConsole: () -> Unit) {
    val adminStr = buildAnnotatedString {
        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) {
            append(stringResource(id = R.string.settings_admin_prefix))
        }

        pushStringAnnotation(tag = "link", annotation = Links.ADMIN_URL)
        withStyle(style = SpanStyle(color = Color.Blue)) {
            append(stringResource(id = R.string.settings_admin_link))
        }
        pop()
    }

    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        ClickableText(
                text = adminStr,
                style = MaterialTheme.typography.bodySmall,
                onClick = {
                    onNavigateToAdminConsole()
                })
    }
}
