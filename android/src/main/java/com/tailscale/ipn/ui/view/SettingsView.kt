// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.AlwaysNeverUserDecides
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.ShowHide
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.components.Header
import com.tailscale.ipn.ui.components.ListItemSwitch
import com.tailscale.ipn.ui.components.ListItemText
import com.tailscale.ipn.ui.dataSource.SettingsDataSource
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.theme.link
import com.tailscale.ipn.ui.util.AndroidTVUtil.isAndroidTV
import com.tailscale.ipn.ui.util.AppVersion
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.viewModel.AppViewModel
import com.tailscale.ipn.ui.viewModel.MainViewModel
import com.tailscale.ipn.ui.viewModel.SettingsNav
import com.tailscale.ipn.ui.viewModel.SettingsViewModel
import com.tailscale.ipn.ui.viewModel.SettingsViewModelFactory
import kotlin.Boolean

@Composable
fun SettingsView(
    settingsNav: SettingsNav,
    dataSource: SettingsDataSource,
    mainViewModel: MainViewModel = viewModel(),
    appViewModel: AppViewModel = viewModel(),
) {
  val handler = LocalUriHandler.current

  val factory = remember(dataSource) { SettingsViewModelFactory(dataSource) }
  val settingsViewModel: SettingsViewModel = viewModel(factory = factory)

  val user by mainViewModel.loggedInUser.collectAsStateWithLifecycle()
  val managedByOrganization by mainViewModel.managedByOrganization.collectAsStateWithLifecycle()
  val isAdmin by settingsViewModel.isAdmin.collectAsStateWithLifecycle()
  val tailnetLockEnabled by settingsViewModel.tailNetLockEnabled.collectAsStateWithLifecycle()
  val corpDNSEnabled by settingsViewModel.corpDNSEnabled.collectAsStateWithLifecycle()
  val isClientRemoteLoggingEnabled by
      settingsViewModel.isClientRemoteLoggingEnabled.collectAsStateWithLifecycle()
  val isVPNPrepared by appViewModel.vpnPrepared.collectAsStateWithLifecycle()
  val showTailnetLock by MDMSettings.manageTailnetLock.flow.collectAsStateWithLifecycle()
  val useTailscaleSubnets by MDMSettings.useTailscaleSubnets.flow.collectAsStateWithLifecycle()

  var showDisableLoggingDialog by remember { mutableStateOf(false) }

  SettingsScreen(
      state =
          SettingsUIState(
              user = user,
              isAdmin = isAdmin,
              managedByOrganization = managedByOrganization.value,
              tailnetLockEnabled = tailnetLockEnabled,
              corpDNSEnabled = corpDNSEnabled,
              isVPNPrepared = isVPNPrepared,
              showTailnetLock = showTailnetLock.value == ShowHide.Show,
              showSubnetRouting = useTailscaleSubnets.value == AlwaysNeverUserDecides.UserDecides,
              isClientRemoteLoggingEnabled = isClientRemoteLoggingEnabled,
              isMDMConfigured = MDMSettings.isMDMConfigured,
              isDebugBuild = BuildConfig.DEBUG,
              appVersion = AppVersion.Short(),
          ),
      nav = settingsNav,
      isTv = isAndroidTV(),
      toggleIsClientRemoteLoggingEnabled = settingsViewModel::toggleIsClientRemoteLoggingEnabled,
      showDisableLoggingDialog = { showDisableLoggingDialog = true },
      openAdmin = { handler.openUri(Links.ADMIN_URL) },
  )

  if (showDisableLoggingDialog) {
    AlertDialog(
        onDismissRequest = { showDisableLoggingDialog = false },
        title = { Text(stringResource(R.string.client_remote_logging_disable_confirm_title)) },
        text = { Text(stringResource(R.string.client_remote_logging_disable_confirm_message)) },
        confirmButton = {
          TextButton(
              onClick = {
                showDisableLoggingDialog = false
                settingsViewModel.toggleIsClientRemoteLoggingEnabled()
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

@Composable
private fun AdminTextView(onNavigateToAdminConsole: () -> Unit) {
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

private data class SettingsUIState(
    val user: IpnLocal.LoginProfile?,
    val isAdmin: Boolean,
    val managedByOrganization: String?,
    val tailnetLockEnabled: Boolean?,
    val corpDNSEnabled: Boolean?,
    val isVPNPrepared: Boolean,
    val showTailnetLock: Boolean,
    val showSubnetRouting: Boolean,
    val isClientRemoteLoggingEnabled: Boolean,
    val isMDMConfigured: Boolean,
    val isDebugBuild: Boolean,
    val appVersion: String,
)

@Composable
private fun SettingsScreen(
    state: SettingsUIState,
    nav: SettingsNav,
    isTv: Boolean,
    toggleIsClientRemoteLoggingEnabled: () -> Unit,
    showDisableLoggingDialog: () -> Unit,
    openAdmin: () -> Unit,
) {
  Scaffold(
      topBar = {
        Header(
            titleRes = R.string.settings_title,
            onBack = nav.onNavigateBackHome,
            isTv = isTv,
        )
      }
  ) { innerPadding ->
    Column(modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState())) {
      if (state.isVPNPrepared) {
        UserView(
            profile = state.user,
            isTv = isTv,
            actionState = UserActionState.NAV,
            onClick = nav.onNavigateToUserSwitcher,
        )
      }

      if (state.isAdmin && !isTv) {
        Lists.ItemDivider()
        AdminTextView(openAdmin)
      }

      Lists.SectionDivider()
      ListItemText(
          R.string.dns_settings,
          subtitle =
              state.corpDNSEnabled?.let {
                stringResource(
                    if (it) R.string.using_tailscale_dns else R.string.not_using_tailscale_dns
                )
              },
          onClick = nav.onNavigateToDNSSettings,
      )

      Lists.ItemDivider()
      ListItemText(
          R.string.split_tunneling,
          subtitle = stringResource(R.string.filter_apps_allowed_to_access_tailscale),
          onClick = nav.onNavigateToSplitTunneling,
      )

      if (state.showTailnetLock) {
        Lists.ItemDivider()
        ListItemText(
            R.string.tailnet_lock,
            subtitle =
                state.tailnetLockEnabled?.let {
                  stringResource(if (it) R.string.enabled else R.string.disabled)
                },
            onClick = nav.onNavigateToTailnetLock,
        )
      }
      if (state.showSubnetRouting) {
        Lists.ItemDivider()
        ListItemText(R.string.subnet_routing, onClick = nav.onNavigateToSubnetRouting)
      }

      Lists.ItemDivider()
      ListItemSwitch(
          R.string.client_remote_logging_enabled,
          subtitle =
              stringResource(
                  if (state.isMDMConfigured) R.string.client_remote_logging_enabled_subtitle_mdm
                  else R.string.client_remote_logging_enabled_subtitle
              ),
          isOn = state.isClientRemoteLoggingEnabled,
          enabled = !state.isMDMConfigured,
          onToggle = {
            if (state.isClientRemoteLoggingEnabled) {
              showDisableLoggingDialog()
            } else {
              toggleIsClientRemoteLoggingEnabled()
            }
          },
      )

      if (!isTv) {
        Lists.ItemDivider()
        ListItemText(R.string.permissions, onClick = nav.onNavigateToPermissions)
      }

      state.managedByOrganization?.let {
        Lists.ItemDivider()
        ListItemText(
            title = stringResource(R.string.managed_by_orgName, it),
            onClick = nav.onNavigateToManagedBy,
        )
      }

      Lists.SectionDivider()
      ListItemText(R.string.bug_report, onClick = nav.onNavigateToBugReport)

      Lists.ItemDivider()
      ListItemText(
          R.string.about_tailscale,
          subtitle = "${stringResource(id = R.string.version)} ${state.appVersion}",
          onClick = nav.onNavigateToAbout,
      )

      // TODO: put a heading for the debug section
      if (state.isDebugBuild) {
        Lists.SectionDivider()
        Lists.MutedHeader(text = stringResource(R.string.internal_debug_options))
        ListItemText(R.string.mdm_settings, onClick = nav.onNavigateToMDMSettings)
      }
    }
  }
}

@Preview(showSystemUi = true)
@Composable
private fun SettingsPreview() {
  AppTheme {
    SettingsScreen(
        state =
            SettingsUIState(
                user = null,
                isAdmin = true,
                managedByOrganization = "Tails and Scales Inc.",
                tailnetLockEnabled = true,
                corpDNSEnabled = true,
                isVPNPrepared = true,
                showTailnetLock = true,
                showSubnetRouting = true,
                isClientRemoteLoggingEnabled = true,
                isMDMConfigured = true,
                isDebugBuild = true,
                appVersion = "1.104.0",
            ),
        nav =
            SettingsNav(
                onNavigateToBugReport = {},
                onNavigateToAbout = {},
                onNavigateToDNSSettings = {},
                onNavigateToSplitTunneling = {},
                onNavigateToTailnetLock = {},
                onNavigateToSubnetRouting = {},
                onNavigateToMDMSettings = {},
                onNavigateToManagedBy = {},
                onNavigateToUserSwitcher = {},
                onNavigateToPermissions = {},
                onNavigateBackHome = {},
                onBackToSettings = {},
            ),
        isTv = false,
        toggleIsClientRemoteLoggingEnabled = {},
        showDisableLoggingDialog = {},
        openAdmin = {},
    )
  }
}
