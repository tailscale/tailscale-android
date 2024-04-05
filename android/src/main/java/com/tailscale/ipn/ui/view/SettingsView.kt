// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.theme.link
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.viewModel.SettingsNav
import com.tailscale.ipn.ui.viewModel.SettingsViewModel

@Composable
fun SettingsView(settingsNav: SettingsNav, viewModel: SettingsViewModel = viewModel()) {
  val handler = LocalUriHandler.current
  val user = viewModel.loggedInUser.collectAsState().value
  val isAdmin = viewModel.isAdmin.collectAsState().value
  val managedByOrganization = viewModel.managedByOrganization.collectAsState().value

  Scaffold(
      topBar = {
        Header(titleRes = R.string.settings_title, onBack = settingsNav.onBackPressed)
      }) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
          UserView(
              profile = user,
              actionState = UserActionState.NAV,
              onClick = settingsNav.onNavigateToUserSwitcher)

          if (isAdmin) {
            Lists.ItemDivider()
            AdminTextView { handler.openUri(Links.ADMIN_URL) }
          }

          Lists.SectionDivider()
          Setting.Text(R.string.dns_settings, onClick = settingsNav.onNavigateToDNSSettings)

          Lists.ItemDivider()
          Setting.Text(R.string.tailnet_lock, onClick = settingsNav.onNavigateToTailnetLock)

          Lists.ItemDivider()
          Setting.Text(R.string.permissions, onClick = settingsNav.onNavigateToPermissions)

          managedByOrganization?.let {
            Lists.ItemDivider()
            Setting.Text(
                title = stringResource(R.string.managed_by_orgName, it),
                onClick = settingsNav.onNavigateToManagedBy)
          }

          Lists.SectionDivider()
          Setting.Text(R.string.bug_report, onClick = settingsNav.onNavigateToBugReport)

          Lists.ItemDivider()
          Setting.Text(R.string.about_tailscale, onClick = settingsNav.onNavigateToAbout)

          // TODO: put a heading for the debug section
          if (BuildConfig.DEBUG) {
            Lists.SectionDivider()
            Setting.Text(R.string.mdm_settings, onClick = settingsNav.onNavigateToMDMSettings)
          }
        }
      }
}

object Setting {
  @Composable
  fun Text(
      titleRes: Int = 0,
      title: String? = null,
      destructive: Boolean = false,
      enabled: Boolean = true,
      onClick: (() -> Unit)? = null
  ) {
    var modifier: Modifier = Modifier
    if (enabled) {
      onClick?.let { modifier = modifier.clickable(onClick = it) }
    }
    ListItem(
        modifier = modifier,
        colors = MaterialTheme.colorScheme.listItem,
        headlineContent = {
          Text(
              title ?: stringResource(titleRes),
              style = MaterialTheme.typography.bodyMedium,
              color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
        },
    )
  }

  @Composable
  fun Switch(
      titleRes: Int = 0,
      title: String? = null,
      isOn: Boolean,
      enabled: Boolean = true,
      onToggle: (Boolean) -> Unit = {}
  ) {
    ListItem(
        colors = MaterialTheme.colorScheme.listItem,
        headlineContent = {
          Text(
              title ?: stringResource(titleRes),
              style = MaterialTheme.typography.bodyMedium,
          )
        },
        trailingContent = {
          TintedSwitch(checked = isOn, onCheckedChange = onToggle, enabled = enabled)
        })
  }
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
                color = MaterialTheme.colorScheme.link,
                textDecoration = TextDecoration.Underline)) {
          append(stringResource(id = R.string.settings_admin_link))
        }
    pop()
  }

  ListItem(
      headlineContent = {
        Box(modifier = Modifier.padding(vertical = 4.dp)) {
          ClickableText(
              text = adminStr,
              style = MaterialTheme.typography.bodyMedium,
              onClick = { onNavigateToAdminConsole() })
        }
      })
}
