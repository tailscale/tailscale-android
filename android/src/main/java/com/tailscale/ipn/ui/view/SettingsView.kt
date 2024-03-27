// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.theme.ts_color_dark_desctrutive_text
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.itemsWithDividers
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
  val settingsBundles = viewModel.settings.collectAsState().value

  Scaffold(
      topBar = { Header(title = R.string.settings_title, onBack = settingsNav.onBackPressed) }) {
          innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding)) {
          item {
            UserView(
                profile = user,
                actionState = UserActionState.NAV,
                onClick = viewModel.navigation.onNavigateToUserSwitcher)
          }

          if (isAdmin) {
            item {
              Spacer(modifier = Modifier.height(4.dp))
              AdminTextView { handler.openUri(Links.ADMIN_URL) }
            }
          }

          settingsBundles.forEach { bundle ->
            item { Lists.SectionDivider() }

            itemsWithDividers(bundle.settings) { setting -> SettingRow(setting) }
          }
        }
      }
}

@Composable
fun SettingRow(setting: Setting) {
  val enabled = setting.enabled.collectAsState().value
  val swVal = setting.isOn?.collectAsState()?.value ?: false
  val txtVal = setting.value?.collectAsState()?.value

  Box {
    when (setting.type) {
      SettingType.TEXT ->
          ListItem(
              modifier = Modifier.clickable { if (enabled) setting.onClick() },
              headlineContent = {
                Text(
                    setting.title.getString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (setting.destructive) ts_color_dark_desctrutive_text
                        else MaterialTheme.colorScheme.primary)
              },
          )
      SettingType.SWITCH ->
          ListItem(
              modifier = Modifier.clickable { if (enabled) setting.onClick() },
              headlineContent = { Text(setting.title.getString()) },
              trailingContent = {
                TintedSwitch(checked = swVal, onCheckedChange = setting.onToggle, enabled = enabled)
              })
      SettingType.NAV -> {
        ListItem(
            modifier = Modifier.clickable { if (enabled) setting.onClick() },
            headlineContent = {
              Text(
                  setting.title.getString(),
                  style = MaterialTheme.typography.bodyMedium,
                  color =
                      if (setting.destructive) ts_color_dark_desctrutive_text
                      else MaterialTheme.colorScheme.primary)
            },
            supportingContent = {
              txtVal?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
            })
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
        onClick = { onNavigateToAdminConsole() })
  }
}
