// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.theme.ts_color_dark_desctrutive_text
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.viewModel.Setting
import com.tailscale.ipn.ui.viewModel.SettingType
import com.tailscale.ipn.ui.viewModel.SettingsNav
import com.tailscale.ipn.ui.viewModel.SettingsViewModel
import com.tailscale.ipn.ui.viewModel.SettingsViewModelFactory

@Composable
fun SettingsView(
    settingsNav: SettingsNav,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModelFactory(settingsNav))
) {
  val handler = LocalUriHandler.current
  val user = viewModel.loggedInUser.collectAsState().value
  val isAdmin = viewModel.isAdmin.collectAsState().value
  val managedBy = viewModel.managedBy.collectAsState().value

  Scaffold(
      topBar = { Header(title = R.string.settings_title, onBack = settingsNav.onBackPressed) }) {
          innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
          UserView(
              profile = user,
              actionState = UserActionState.NAV,
              onClick = viewModel.navigation.onNavigateToUserSwitcher)

          if (isAdmin) {
            Spacer(modifier = Modifier.height(4.dp))
            AdminTextView { handler.openUri(Links.ADMIN_URL) }
          }

          SettingRow(viewModel.dns)

          Lists.ItemDivider()
          SettingRow(viewModel.tailnetLock)

          Lists.ItemDivider()
          SettingRow(viewModel.permissions)

          Lists.ItemDivider()
          SettingRow(viewModel.about)

          Lists.ItemDivider()
          SettingRow(viewModel.bugReport)

          if (BuildConfig.DEBUG) {
            Lists.ItemDivider()
            SettingRow(viewModel.mdmDebug)
          }

          managedBy?.let {
            Lists.ItemDivider()
            SettingRow(it)
          }
        }
      }
}

@Composable
fun SettingRow(setting: Setting) {
  Box {
    when (setting.type) {
      SettingType.TEXT -> TextRow(setting)
      SettingType.SWITCH -> SwitchRow(setting)
      SettingType.NAV -> {
        NavRow(setting)
      }
    }
  }
}

@Composable
private fun TextRow(setting: Setting) {
  val enabled = setting.enabled.collectAsState().value
  ListItem(
      modifier = Modifier.clickable { if (enabled) setting.onClick() },
      headlineContent = {
        Text(
            setting.title ?: stringResource(setting.titleRes),
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (setting.destructive) ts_color_dark_desctrutive_text
                else MaterialTheme.colorScheme.primary)
      },
  )
}

@Composable
private fun SwitchRow(setting: Setting) {
  val enabled = setting.enabled.collectAsState().value
  val swVal = setting.isOn?.collectAsState()?.value ?: false
  ListItem(
      modifier = Modifier.clickable { if (enabled) setting.onClick() },
      headlineContent = {
        Text(
            setting.title ?: stringResource(setting.titleRes),
            style = MaterialTheme.typography.bodyMedium,
        )
      },
      trailingContent = {
        TintedSwitch(checked = swVal, onCheckedChange = setting.onToggle, enabled = enabled)
      })
}

@Composable
private fun NavRow(setting: Setting) {
  ListItem(
      modifier = Modifier.clickable { setting.onClick() },
      headlineContent = {
        Text(
            setting.title ?: stringResource(setting.titleRes),
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (setting.destructive) ts_color_dark_desctrutive_text
                else MaterialTheme.colorScheme.primary)
      })
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
        onClick = { onNavigateToAdminConsole() })
  }
}
