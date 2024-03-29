// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.theme.listItem
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
            AdminTextView { handler.openUri(Links.ADMIN_URL) }
          }

          Lists.SectionDivider()
          SettingRow(viewModel.dns)

          Lists.ItemDivider()
          SettingRow(viewModel.tailnetLock)

          Lists.ItemDivider()
          SettingRow(viewModel.permissions)

          managedBy?.let {
            Lists.ItemDivider()
            SettingRow(it)
          }

          Lists.SectionDivider()
          SettingRow(viewModel.bugReport)

          Lists.ItemDivider()
          SettingRow(viewModel.about)

          // TODO: put a heading for the debug section
          if (BuildConfig.DEBUG) {
            Lists.SectionDivider()
            SettingRow(viewModel.mdmDebug)
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
      colors = MaterialTheme.colorScheme.listItem,
      headlineContent = {
        Text(
            setting.title ?: stringResource(setting.titleRes),
            style = MaterialTheme.typography.bodyMedium,
            color = if (setting.destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
      },
  )
}

@Composable
private fun SwitchRow(setting: Setting) {
  val enabled = setting.enabled.collectAsState().value
  val swVal = setting.isOn?.collectAsState()?.value ?: false
  ListItem(
      modifier = Modifier.clickable { if (enabled) setting.onClick() },
      colors = MaterialTheme.colorScheme.listItem,
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
      colors = MaterialTheme.colorScheme.listItem,
      headlineContent = {
        Text(
            setting.title ?: stringResource(setting.titleRes),
            style = MaterialTheme.typography.bodyMedium)
      })
}

@Composable
fun AdminTextView(onNavigateToAdminConsole: () -> Unit) {
  val adminStr = buildAnnotatedString {
    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
      append(stringResource(id = R.string.settings_admin_prefix))
    }

    pushStringAnnotation(tag = "link", annotation = Links.ADMIN_URL)
    withStyle(
        style =
            SpanStyle(
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textDecoration = TextDecoration.Underline)) {
          append(stringResource(id = R.string.settings_admin_link))
        }
    pop()
  }

  ListItem(
      headlineContent = {
        ClickableText(
            text = adminStr,
            style = MaterialTheme.typography.bodyMedium,
            onClick = { onNavigateToAdminConsole() })
      })
}
