// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.AlwaysNeverUserDecides
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.ShowHide
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.theme.link
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.util.AndroidTVUtil
import com.tailscale.ipn.ui.util.AndroidTVUtil.isAndroidTV
import com.tailscale.ipn.ui.util.AppVersion
import com.tailscale.ipn.ui.util.ListGroup
import com.tailscale.ipn.ui.util.ListRow
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.AppViewModel
import com.tailscale.ipn.ui.viewModel.SettingsNav
import com.tailscale.ipn.ui.viewModel.SettingsViewModel

@Composable
fun SettingsView(
    settingsNav: SettingsNav,
    viewModel: SettingsViewModel = viewModel(),
    appViewModel: AppViewModel = viewModel(),
    // False when rendered in the detail pane of the list-detail layout, where the list stays
    // visible alongside and there is nothing to go back to.
    showBack: Boolean = true,
) {
  val handler = LocalUriHandler.current

  val user by viewModel.loggedInUser.collectAsState()
  val isAdmin by viewModel.isAdmin.collectAsState()
  val managedByOrganization by viewModel.managedByOrganization.collectAsState()
  val tailnetLockEnabled by viewModel.tailNetLockEnabled.collectAsState()
  val corpDNSEnabled by viewModel.corpDNSEnabled.collectAsState()
  val isVPNPrepared by appViewModel.vpnPrepared.collectAsState()
  val showTailnetLock by MDMSettings.manageTailnetLock.flow.collectAsState()
  val useTailscaleSubnets by MDMSettings.useTailscaleSubnets.flow.collectAsState()
  val isClientRemoteLoggingEnabled by viewModel.isClientRemoteLoggingEnabled.collectAsState()
  var showDisableLoggingDialog by remember { mutableStateOf(false) }
  val showExitNodePicker by MDMSettings.exitNodesPicker.flow.collectAsState()
  val prefs by viewModel.prefs.collectAsState()
  val netmap by viewModel.netmap.collectAsState()
  // The active node is the source of truth; the selected one only matters without an active one.
  val exitNodeName =
      (prefs?.activeExitNodeID ?: prefs?.selectedExitNodeID)?.let { id ->
        netmap?.Peers?.find { it.StableID == id }?.exitNodeName
      }

  Scaffold(
      topBar = {
        Header(
            titleRes = R.string.settings_title,
            onBack = settingsNav.onNavigateBackHome.takeIf { showBack },
        )
      }
  ) { innerPadding ->
    Column(modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState())) {
      if (isVPNPrepared) {
        ListGroup {
          UserView(
              profile = user,
              actionState = UserActionState.NAV,
              onClick = settingsNav.onNavigateToUserSwitcher,
          )
        }
      }

      if (isAdmin && !isAndroidTV()) {
        AdminTextView { handler.openUri(Links.ADMIN_URL) }
      }

      Lists.SectionDivider()
      ListGroup {
        // A TV's home screen has no room for the exit node row, so it lives here instead.
        if (isAndroidTV() && showExitNodePicker.value == ShowHide.Show) {
          Setting.Text(
              R.string.choose_exit_node,
              subtitle = exitNodeName ?: stringResource(R.string.none),
              onClick = settingsNav.onNavigateToExitNodes,
          )
          Lists.ItemDivider()
        }
        Setting.Text(
            R.string.dns_settings,
            subtitle =
                corpDNSEnabled?.let {
                  stringResource(
                      if (it) R.string.using_tailscale_dns else R.string.not_using_tailscale_dns
                  )
                },
            onClick = settingsNav.onNavigateToDNSSettings,
        )

        Lists.ItemDivider()
        Setting.Text(
            R.string.split_tunneling,
            subtitle = stringResource(R.string.filter_apps_allowed_to_access_tailscale),
            onClick = settingsNav.onNavigateToSplitTunneling,
        )

        if (showTailnetLock.value == ShowHide.Show) {
          Lists.ItemDivider()
          Setting.Text(
              R.string.tailnet_lock,
              subtitle =
                  tailnetLockEnabled?.let {
                    stringResource(if (it) R.string.enabled else R.string.disabled)
                  },
              onClick = settingsNav.onNavigateToTailnetLock,
          )
        }
        if (useTailscaleSubnets.value == AlwaysNeverUserDecides.UserDecides) {
          Lists.ItemDivider()
          Setting.Text(R.string.subnet_routing, onClick = settingsNav.onNavigateToSubnetRouting)
        }

        Lists.ItemDivider()
        Setting.Switch(
            R.string.client_remote_logging_enabled,
            subtitle =
                stringResource(
                    if (MDMSettings.isMDMConfigured)
                        R.string.client_remote_logging_enabled_subtitle_mdm
                    else R.string.client_remote_logging_enabled_subtitle
                ),
            isOn = isClientRemoteLoggingEnabled,
            enabled = !MDMSettings.isMDMConfigured,
            onToggle = {
              if (isClientRemoteLoggingEnabled) {
                showDisableLoggingDialog = true
              } else {
                viewModel.toggleIsClientRemoteLoggingEnabled()
              }
            },
        )

        if (!AndroidTVUtil.isAndroidTV()) {
          Lists.ItemDivider()
          Setting.Text(R.string.permissions, onClick = settingsNav.onNavigateToPermissions)
        }

        managedByOrganization.value?.let {
          Lists.ItemDivider()
          Setting.Text(
              title = stringResource(R.string.managed_by_orgName, it),
              onClick = settingsNav.onNavigateToManagedBy,
          )
        }
      }

      Lists.SectionDivider()
      ListGroup {
        Setting.Text(R.string.bug_report, onClick = settingsNav.onNavigateToBugReport)

        Lists.ItemDivider()
        Setting.Text(
            R.string.about_tailscale,
            subtitle = "${stringResource(id = R.string.version)} ${AppVersion.Short()}",
            onClick = settingsNav.onNavigateToAbout,
        )
      }

      // TODO: put a heading for the debug section
      if (BuildConfig.DEBUG) {
        Lists.SectionDivider()
        Lists.MutedHeader(text = stringResource(R.string.internal_debug_options))
        ListGroup {
          Setting.Text(R.string.mdm_settings, onClick = settingsNav.onNavigateToMDMSettings)
        }
      }
    }
  }

  if (showDisableLoggingDialog) {
    AlertDialog(
        onDismissRequest = { showDisableLoggingDialog = false },
        title = { Text(stringResource(R.string.client_remote_logging_disable_confirm_title)) },
        text = { Text(stringResource(R.string.client_remote_logging_disable_confirm_message)) },
        confirmButton = {
          TextButton(
              onClick = {
                showDisableLoggingDialog = false
                viewModel.toggleIsClientRemoteLoggingEnabled()
              }
          ) {
            Text(
                stringResource(R.string.client_remote_logging_disable_confirm_button),
                color = MaterialTheme.colorScheme.error,
            )
          }
        },
        dismissButton = {
          TextButton(onClick = { showDisableLoggingDialog = false }) {
            Text(stringResource(R.string.cancel))
          }
        },
    )
  }
}

object Setting {
  @Composable
  fun Text(
      titleRes: Int = 0,
      title: String? = null,
      subtitle: String? = null,
      destructive: Boolean = false,
      enabled: Boolean = true,
      onClick: (() -> Unit)? = null,
  ) {
    var modifier: Modifier = Modifier
    if (enabled) {
      onClick?.let { modifier = modifier.clickable(onClick = it) }
    }
    ListRow(
        modifier = modifier,
        colors = MaterialTheme.colorScheme.listItem,
        headlineContent = {
          Text(
              title ?: stringResource(titleRes),
              style = MaterialTheme.typography.bodyMedium,
              color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
          )
        },
        supportingContent =
            subtitle?.let {
              {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            },
    )
  }

  @Composable
  fun Switch(
      titleRes: Int = 0,
      title: String? = null,
      subtitle: String? = null,
      isOn: Boolean,
      enabled: Boolean = true,
      onToggle: (Boolean) -> Unit = {},
  ) {
    ListRow(
        colors = MaterialTheme.colorScheme.listItem,
        headlineContent = {
          Text(
              title ?: stringResource(titleRes),
              style = MaterialTheme.typography.bodyMedium,
          )
        },
        supportingContent =
            subtitle?.let {
              {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            },
        trailingContent = {
          TintedSwitch(checked = isOn, onCheckedChange = onToggle, enabled = enabled)
        },
    )
  }
}

@Composable
fun AdminTextView(onNavigateToAdminConsole: () -> Unit) {
  val adminStr = buildAnnotatedString {
    append(stringResource(id = R.string.settings_admin_prefix))

    pushStringAnnotation(tag = "link", annotation = Links.ADMIN_URL)
    withStyle(
        style =
            SpanStyle(
                color = MaterialTheme.colorScheme.link,
                textDecoration = TextDecoration.Underline,
            )
    ) {
      append(stringResource(id = R.string.settings_admin_link))
    }
  }

  Lists.InfoItem(adminStr, onClick = onNavigateToAdminConsole)
}

@Preview
@Composable
fun SettingsPreview() {
  val vm = SettingsViewModel()
  vm.corpDNSEnabled.set(true)
  vm.tailNetLockEnabled.set(true)
  vm.isAdmin.set(true)
  vm.managedByOrganization.set("Tails and Scales Inc.")
  SettingsView(SettingsNav({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}), vm)
}
