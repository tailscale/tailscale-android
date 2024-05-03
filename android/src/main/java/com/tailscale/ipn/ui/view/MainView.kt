// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.ShowHide
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Permissions
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.theme.disabled
import com.tailscale.ipn.ui.theme.exitNodeToggleButton
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.theme.minTextSize
import com.tailscale.ipn.ui.theme.primaryListItem
import com.tailscale.ipn.ui.theme.searchBarColors
import com.tailscale.ipn.ui.theme.secondaryButton
import com.tailscale.ipn.ui.theme.short
import com.tailscale.ipn.ui.theme.surfaceContainerListItem
import com.tailscale.ipn.ui.theme.warningListItem
import com.tailscale.ipn.ui.util.AutoResizingText
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.flag
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.MainViewModel

// Navigation actions for the MainView
data class MainViewNavigation(
    val onNavigateToSettings: () -> Unit,
    val onNavigateToPeerDetails: (Tailcfg.Node) -> Unit,
    val onNavigateToExitNodes: () -> Unit
)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainView(
    loginAtUrl: (String) -> Unit,
    navigation: MainViewNavigation,
    viewModel: MainViewModel = viewModel()
) {
  val isOn = viewModel.vpnToggleState.collectAsState()
  LoadingIndicator.Wrap {
    Scaffold(contentWindowInsets = WindowInsets.Companion.statusBars) { paddingInsets ->
      Column(
          modifier = Modifier.fillMaxWidth().padding(paddingInsets),
          verticalArrangement = Arrangement.Center) {
            val state by viewModel.ipnState.collectAsState(initial = Ipn.State.NoState)
            val user by viewModel.loggedInUser.collectAsState(initial = null)
            val stateVal by viewModel.stateRes.collectAsState(initial = R.string.placeholder)
            val stateStr = stringResource(id = stateVal)
            val netmap by viewModel.netmap.collectAsState(initial = null)
            val showExitNodePicker by MDMSettings.exitNodesPicker.flow.collectAsState()
            val disableToggle by MDMSettings.forceEnabled.flow.collectAsState(initial = true)
            val showKeyExpiry by viewModel.showExpiry.collectAsState(initial = false)

            ListItem(
                colors = MaterialTheme.colorScheme.surfaceContainerListItem,
                leadingContent = {
                  TintedSwitch(
                      onCheckedChange = {
                        if (!disableToggle) {
                          viewModel.toggleVpn()
                        }
                      },
                      enabled = !disableToggle,
                      checked = isOn.value)
                },
                headlineContent = {
                  user?.NetworkProfile?.DomainName?.let { domain ->
                    AutoResizingText(
                        text = domain,
                        style = MaterialTheme.typography.titleMedium.short,
                        minFontSize = MaterialTheme.typography.minTextSize,
                        overflow = TextOverflow.Ellipsis)
                  }
                },
                supportingContent = {
                  Text(text = stateStr, style = MaterialTheme.typography.bodyMedium.short)
                },
                trailingContent = {
                  Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    when (user) {
                      null -> SettingsButton { navigation.onNavigateToSettings() }
                      else ->
                          Box(
                              contentAlignment = Alignment.Center,
                              modifier =
                                  Modifier.size(42.dp).clip(CircleShape).clickable {
                                    navigation.onNavigateToSettings()
                                  }) {
                                Avatar(profile = user, size = 36) {
                                  navigation.onNavigateToSettings()
                                }
                              }
                    }
                  }
                })

            when (state) {
              Ipn.State.Running -> {

                PromptPermissionsIfNecessary()

                if (showKeyExpiry) {
                  ExpiryNotification(netmap = netmap, action = { viewModel.login() })
                }

                if (showExitNodePicker == ShowHide.Show) {
                  ExitNodeStatus(
                      navAction = navigation.onNavigateToExitNodes, viewModel = viewModel)
                }

                PeerList(
                    viewModel = viewModel,
                    onNavigateToPeerDetails = navigation.onNavigateToPeerDetails,
                    onSearch = { viewModel.searchPeers(it) })
              }
              Ipn.State.NoState,
              Ipn.State.Starting -> StartingView()
              else -> {
                ConnectView(
                    state,
                    user,
                    { viewModel.toggleVpn() },
                    { viewModel.login() },
                    loginAtUrl,
                    netmap?.SelfNode)
              }
            }
          }
    }
  }
}

@Composable
fun ExitNodeStatus(navAction: () -> Unit, viewModel: MainViewModel) {
  val maybePrefs = viewModel.prefs.collectAsState()
  val netmap = viewModel.netmap.collectAsState()

  // There's nothing to render if we haven't loaded the prefs yet
  val prefs = maybePrefs.value ?: return

  // The activeExitNode is the source of truth.  The selectedExitNode is only relevant if we
  // don't have an active node.
  val chosenExitNodeId = prefs.activeExitNodeID ?: prefs.selectedExitNodeID

  val exitNodePeer = chosenExitNodeId?.let { id -> netmap.value?.Peers?.find { it.StableID == id } }
  val location = exitNodePeer?.Hostinfo?.Location
  val name = exitNodePeer?.ComputedName

  // We're connected to an exit node if we found an active peer for the *active* exit node
  val activeAndRunning = (exitNodePeer != null) && !prefs.activeExitNodeID.isNullOrEmpty()

  // (jonathan) TODO: We will block the "enable/disable" button for an exit node for which we cannot
  // find a peer  on purpose and render the "No Exit Node" state, however, that should
  // eventually show up in the UI as an error case so the user knows to pick an available node.

  Box(modifier = Modifier.background(color = MaterialTheme.colorScheme.surfaceContainer)) {
    Box(
        modifier =
            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
                .clip(shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 10.dp))
                .fillMaxWidth()) {
          ListItem(
              modifier = Modifier.clickable { navAction() },
              colors =
                  if (activeAndRunning) MaterialTheme.colorScheme.primaryListItem
                  else ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
              overlineContent = {
                Text(
                    stringResource(R.string.exit_node),
                    style = MaterialTheme.typography.bodySmall,
                )
              },
              headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                      text =
                          location?.let { "${it.CountryCode?.flag()} ${it.Country}: ${it.City}" }
                              ?: name
                              ?: stringResource(id = R.string.none),
                      style = MaterialTheme.typography.bodyMedium,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis)
                  Icon(
                      imageVector = Icons.Outlined.ArrowDropDown,
                      contentDescription = null,
                      tint =
                          if (activeAndRunning)
                              MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                          else MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              },
              trailingContent = {
                if (exitNodePeer != null) {
                  Button(
                      colors =
                          if (prefs.activeExitNodeID.isNullOrEmpty())
                              MaterialTheme.colorScheme.exitNodeToggleButton
                          else MaterialTheme.colorScheme.secondaryButton,
                      onClick = { viewModel.toggleExitNode() }) {
                        Text(
                            if (prefs.activeExitNodeID.isNullOrEmpty())
                                stringResource(id = R.string.enable)
                            else stringResource(id = R.string.disable))
                      }
                }
              })
        }
  }
}

@Composable
fun SettingsButton(action: () -> Unit) {
  IconButton(modifier = Modifier.size(24.dp), onClick = { action() }) {
    Icon(
        Icons.Outlined.Settings,
        contentDescription = "Open settings",
        tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
fun StartingView() {
  Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally) {
        TailscaleLogoView(
            animated = true, usesOnBackgroundColors = false, Modifier.size(40.dp).alpha(0.3f))
      }
}

@Composable
fun ConnectView(
    state: Ipn.State,
    user: IpnLocal.LoginProfile?,
    connectAction: () -> Unit,
    loginAction: () -> Unit,
    loginAtUrlAction: (String) -> Unit,
    selfNode: Tailcfg.Node?
) {
  Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
      Column(
          modifier = Modifier.padding(8.dp).fillMaxWidth(0.7f).fillMaxHeight(),
          verticalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.CenterVertically),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        if (state == Ipn.State.NeedsMachineAuth) {
          Icon(
              modifier = Modifier.size(40.dp),
              imageVector = Icons.Outlined.Lock,
              contentDescription = "Device requires authentication")
          Text(
              text = stringResource(id = R.string.machine_auth_required),
              style = MaterialTheme.typography.titleMedium,
              textAlign = TextAlign.Center)
          Text(
              text = stringResource(id = R.string.machine_auth_explainer),
              style = MaterialTheme.typography.bodyMedium,
              textAlign = TextAlign.Center)
          Spacer(modifier = Modifier.size(1.dp))
          selfNode?.let {
            PrimaryActionButton(onClick = { loginAtUrlAction(it.nodeAdminUrl) }) {
              Text(
                  text = stringResource(id = R.string.open_admin_console),
                  fontSize = MaterialTheme.typography.titleMedium.fontSize)
            }
          }
        } else if (state != Ipn.State.NeedsLogin && user != null && !user.isEmpty()) {
          Icon(
              painter = painterResource(id = R.drawable.power),
              contentDescription = null,
              modifier = Modifier.size(40.dp),
              tint = MaterialTheme.colorScheme.disabled)
          Text(
              text = stringResource(id = R.string.not_connected),
              fontSize = MaterialTheme.typography.titleMedium.fontSize,
              fontWeight = FontWeight.SemiBold,
              textAlign = TextAlign.Center,
              fontFamily = MaterialTheme.typography.titleMedium.fontFamily)
          val tailnetName = user.NetworkProfile?.DomainName ?: ""
          Text(
              buildAnnotatedString {
                append(stringResource(id = R.string.connect_to_tailnet_prefix))
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(tailnetName)
                pop()
                append(stringResource(id = R.string.connect_to_tailnet_suffix))
              },
              fontSize = MaterialTheme.typography.titleMedium.fontSize,
              fontWeight = FontWeight.Normal,
              textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.size(1.dp))
          PrimaryActionButton(onClick = connectAction) {
            Text(
                text = stringResource(id = R.string.connect),
                fontSize = MaterialTheme.typography.titleMedium.fontSize)
          }
        } else {
          TailscaleLogoView(modifier = Modifier.size(50.dp))
          Spacer(modifier = Modifier.size(1.dp))
          Text(
              text = stringResource(id = R.string.welcome_to_tailscale),
              style = MaterialTheme.typography.titleMedium,
              textAlign = TextAlign.Center)
          Text(
              stringResource(R.string.login_to_join_your_tailnet),
              style = MaterialTheme.typography.titleSmall,
              textAlign = TextAlign.Center)
          Spacer(modifier = Modifier.size(1.dp))
          PrimaryActionButton(onClick = loginAction) {
            Text(
                text = stringResource(id = R.string.log_in),
                fontSize = MaterialTheme.typography.titleMedium.fontSize)
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PeerList(
    viewModel: MainViewModel,
    onNavigateToPeerDetails: (Tailcfg.Node) -> Unit,
    onSearch: (String) -> Unit
) {
  val peerList = viewModel.peers.collectAsState(initial = emptyList<PeerSet>())
  val searchTermStr by viewModel.searchTerm.collectAsState(initial = "")
  val showNoResults =
      remember { derivedStateOf { searchTermStr.isNotEmpty() && peerList.value.isEmpty() } }.value

  val netmap = viewModel.netmap.collectAsState()

  val focusManager = LocalFocusManager.current
  var isFocussed by remember { mutableStateOf(false) }

  Box(modifier = Modifier.fillMaxWidth().background(color = MaterialTheme.colorScheme.surface)) {
    OutlinedTextField(
        modifier =
            Modifier.fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 0.dp)
                .onFocusChanged { isFocussed = it.isFocused },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        colors = MaterialTheme.colorScheme.searchBarColors,
        leadingIcon = { Icon(imageVector = Icons.Outlined.Search, contentDescription = "search") },
        trailingIcon = {
          if (isFocussed) {
            IconButton(
                onClick = {
                  focusManager.clearFocus()
                  onSearch("")
                }) {
                  Icon(
                      imageVector =
                          if (searchTermStr.isEmpty()) Icons.Outlined.Close
                          else Icons.Outlined.Clear,
                      contentDescription = "clear search",
                      tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
          }
        },
        placeholder = {
          Text(
              text = stringResource(id = R.string.search),
              style = MaterialTheme.typography.bodyLarge,
              maxLines = 1)
        },
        value = searchTermStr,
        onValueChange = { onSearch(it) })
  }

  LazyColumn(
      modifier = Modifier.fillMaxSize().background(color = MaterialTheme.colorScheme.surface)) {
        if (showNoResults) {
          item {
            Spacer(
                Modifier.height(16.dp)
                    .fillMaxSize()
                    .background(color = MaterialTheme.colorScheme.surface))

            Lists.LargeTitle(
                stringResource(id = R.string.no_results),
                bottomPadding = 8.dp,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Light)
          }
        }

        var first = true
        peerList.value.forEach { peerSet ->
          if (!first) {
            item(key = "user_divider_${peerSet.user?.ID ?: 0L}") { Lists.ItemDivider() }
          }
          first = false

          stickyHeader {
            Spacer(
                Modifier.height(16.dp)
                    .fillMaxSize()
                    .background(color = MaterialTheme.colorScheme.surface))

            Lists.LargeTitle(
                peerSet.user?.DisplayName ?: stringResource(id = R.string.unknown_user),
                bottomPadding = 8.dp,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
          }

          itemsWithDividers(peerSet.peers, key = { it.StableID }) { peer ->
            ListItem(
                modifier = Modifier.clickable { onNavigateToPeerDetails(peer) },
                colors = MaterialTheme.colorScheme.listItem,
                headlineContent = {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier.padding(top = 2.dp)
                                .size(10.dp)
                                .background(
                                    color = peer.connectedColor(netmap.value),
                                    shape = RoundedCornerShape(percent = 50))) {}
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = peer.displayName, style = MaterialTheme.typography.titleMedium)
                  }
                },
                supportingContent = {
                  Text(
                      text = peer.Addresses?.first()?.split("/")?.first() ?: "",
                      style =
                          MaterialTheme.typography.bodyMedium.copy(
                              lineHeight = MaterialTheme.typography.titleMedium.lineHeight))
                })
          }
        }
      }
}

@Composable
fun ExpiryNotification(netmap: Netmap.NetworkMap?, action: () -> Unit = {}) {
  if (netmap == null) return

  Box(modifier = Modifier.background(color = MaterialTheme.colorScheme.surfaceContainer)) {
    Box(
        modifier =
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                .clip(shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 10.dp))
                .fillMaxWidth()) {
          ListItem(
              modifier = Modifier.clickable { action() },
              colors = MaterialTheme.colorScheme.warningListItem,
              headlineContent = {
                Text(
                    netmap.SelfNode.expiryLabel(),
                    style = MaterialTheme.typography.titleMedium,
                )
              },
              supportingContent = {
                Text(
                    stringResource(id = R.string.keyExpiryExplainer),
                    style = MaterialTheme.typography.bodyMedium)
              })
        }
  }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PromptPermissionsIfNecessary() {
  Permissions.prompt.forEach { (permission, state) ->
    ErrorDialog(
        title = permission.title,
        message = permission.description,
        buttonText = R.string._continue) {
          state.launchPermissionRequest()
        }
  }
}

@Preview
@Composable
fun MainViewPreview() {
  val vm = MainViewModel()
  MainView(
      {},
      MainViewNavigation(
          onNavigateToSettings = {}, onNavigateToPeerDetails = {}, onNavigateToExitNodes = {}),
      vm)
}
