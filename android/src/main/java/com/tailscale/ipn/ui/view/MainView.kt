// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.ShowHide
import com.tailscale.ipn.ui.dataSource.PingDataSource
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Permission
import com.tailscale.ipn.ui.model.Permissions
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.theme.minTextSize
import com.tailscale.ipn.ui.theme.short
import com.tailscale.ipn.ui.theme.surfaceContainerListItem
import com.tailscale.ipn.ui.util.AndroidTVUtil.isAndroidTV
import com.tailscale.ipn.ui.util.AutoResizingText
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.view.main.ConnectView
import com.tailscale.ipn.ui.view.main.ExitNodeStatus
import com.tailscale.ipn.ui.view.main.ExpiryNotification
import com.tailscale.ipn.ui.view.main.PeerList
import com.tailscale.ipn.ui.viewModel.MainViewModel

// Navigation actions for the MainView
data class MainViewNavigation(
    val onNavigateToSettings: () -> Unit,
    val onNavigateToPeerDetails: (Tailcfg.Node) -> Unit,
    val onNavigateToExitNodes: () -> Unit,
    val onNavigateToHealth: () -> Unit,
    val onNavigateToSearch: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainView(
    loginAtUrl: (String) -> Unit,
    navigation: MainViewNavigation,
    viewModel: MainViewModel,
    pingDataSource: PingDataSource,
    isTv: Boolean = isAndroidTV(),
) {
  val currentPingDevice by viewModel.pingPeer.collectAsState()
  val healthIcon by viewModel.healthIcon.collectAsState()
  val maybePrefs by viewModel.prefs.collectAsState()
  val netmap by viewModel.netmap.collectAsState()
  val isOn by viewModel.vpnToggleState.collectAsState()
  val ipnState by viewModel.ipnState.collectAsState()
  val user by viewModel.loggedInUser.collectAsState()
  val stateVal by viewModel.stateRes.collectAsState(initial = R.string.placeholder)
  val isToggleInProgress by viewModel.isToggleInProgress.collectAsState()
  val showExitNodePicker by MDMSettings.exitNodesPicker.flow.collectAsState()
  val disableToggle by MDMSettings.forceEnabled.flow.collectAsState()
  val showKeyExpiry by viewModel.showExpiry.collectAsState()
  val pending by viewModel.pendingTaildrop.pendingItems.collectAsState()
  val showPendingSheet by viewModel.pendingTaildrop.isPresentingPendingItemsList.collectAsState()
  // Disable switch if toggle is in progress
  val switchEnabled = !disableToggle.value && !isToggleInProgress

  LoadingIndicator.Wrap {
    Scaffold(contentWindowInsets = WindowInsets.statusBars) { paddingInsets ->
      Column(
          modifier = Modifier.fillMaxWidth().padding(paddingInsets),
          verticalArrangement = Arrangement.Center,
      ) {
        val hideHeader = (isTv && ipnState == Ipn.State.NeedsLogin)
        ListItem(
            colors = MaterialTheme.colorScheme.surfaceContainerListItem,
            leadingContent = {
              if (!hideHeader) {
                TintedSwitch(
                    checked = isOn,
                    enabled = switchEnabled,
                    onCheckedChange = { desiredState -> viewModel.toggleVpn(desiredState) },
                )
              }
            },
            headlineContent = {
              user?.NetworkProfile?.tailnetNameForDisplay()?.let { domain ->
                AutoResizingText(
                    text = domain,
                    style = MaterialTheme.typography.titleMedium.short,
                    minFontSize = MaterialTheme.typography.minTextSize,
                    overflow = TextOverflow.Ellipsis,
                )
              }
            },
            supportingContent = {
              if (!hideHeader) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  Text(
                      text = stringResource(id = stateVal),
                      style = MaterialTheme.typography.bodyMedium.short,
                  )
                  healthIcon?.let {
                    IconButton(
                        onClick = navigation.onNavigateToHealth,
                        modifier = Modifier.size(16.dp),
                    ) {
                      Icon(
                          painterResource(id = it),
                          contentDescription = null,
                          modifier = Modifier.size(16.dp),
                          tint = MaterialTheme.colorScheme.error,
                      )
                    }
                  }
                }
              }
            },
            trailingContent = {
              Box(
                  modifier = Modifier.padding(8.dp),
                  contentAlignment = Alignment.CenterEnd,
              ) {
                when (user) {
                  null -> SettingsButton(action = navigation.onNavigateToSettings)
                  else ->
                      Avatar(
                          profile = user,
                          size = 36,
                          isTv = isTv,
                          action = navigation.onNavigateToSettings,
                          isFocusable = true,
                      )
                }
              }
            },
        )
        when (ipnState) {
          Ipn.State.Running -> {
            viewModel.maybeRequestVpnPermission()
            LaunchVpnPermissionIfNeeded(viewModel)
            if (!viewModel.skipPromptsForAuthKeyLogin()) {
              PromptForMissingPermissions(prompts = Permissions.prompts)
            }

            if (showKeyExpiry) {
              netmap?.let {
                ExpiryNotification(
                    netmap = it,
                    action = viewModel::login,
                )
              }
            }
            if (showExitNodePicker.value == ShowHide.Show) {
              maybePrefs?.let { prefs ->
                ExitNodeStatus(
                    viewModel = viewModel,
                    navigation = navigation,
                    prefs = prefs,
                )
              }
            }

            if (pending.isNotEmpty()) {
              TaildropBannerView(viewModel = viewModel.pendingTaildrop)
            }

            PeerList(
                viewModel = viewModel,
                navigation = navigation,
            )
          }
          Ipn.State.NoState,
          Ipn.State.Starting -> StartingView()
          else -> {
            ConnectView(
                viewModel = viewModel,
                loginAtUrl = loginAtUrl,
            )
          }
        }
      }
    }
  }

  currentPingDevice?.let { peer ->
    val pingOwner = rememberViewModelStoreOwner()

    CompositionLocalProvider(
        LocalViewModelStoreOwner provides pingOwner,
    ) {
      ModalBottomSheet(
          onDismissRequest = viewModel::onPingDismissal,
          sheetState =
              rememberModalBottomSheetState(
                  skipPartiallyExpanded = true,
              ),
      ) {
        PingView(
            peer = peer,
            dataSource = pingDataSource,
        )
      }
    }
  }

  if (showPendingSheet) {
    ModalBottomSheet(
        onDismissRequest = { viewModel.pendingTaildrop.isPresentingPendingItemsList.value = false },
    ) {
      InlineShareListSheet(viewModel = viewModel.pendingTaildrop)
    }
  }
}

@Composable
private fun LaunchVpnPermissionIfNeeded(viewModel: MainViewModel) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val shouldRequest by viewModel.requestVpnPermission.collectAsState()
  LaunchedEffect(shouldRequest) {
    if (!shouldRequest) return@LaunchedEffect
    // Defer showing permission launcher until activity is resumed to avoid silent RESULT_CANCELED
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      viewModel.showVPNPermissionLauncherIfUnauthorized()
    }
  }
}

@Composable
private fun SettingsButton(action: () -> Unit) {
  IconButton(modifier = Modifier.size(24.dp), onClick = { action() }) {
    Icon(
        Icons.Outlined.Settings,
        contentDescription = "Open settings",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun StartingView() {
  Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    TailscaleLogoView(
        animated = true,
        usesOnBackgroundColors = false,
        Modifier.size(40.dp).alpha(0.3f),
    )
  }
}

@Composable
private fun PromptForMissingPermissions(prompts: List<Pair<Permission, () -> Unit>>) {
  prompts.forEach { (permission, requestPermissions) ->
    ErrorDialog(
        title = permission.title,
        message = permission.description,
        buttonText = R.string._continue,
    ) {
      requestPermissions()
    }
  }
}
