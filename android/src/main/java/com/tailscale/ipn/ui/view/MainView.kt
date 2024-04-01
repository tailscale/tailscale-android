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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Permission
import com.tailscale.ipn.ui.model.Permissions
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.theme.disabled
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.theme.primaryListItem
import com.tailscale.ipn.ui.theme.secondaryButton
import com.tailscale.ipn.ui.theme.surfaceContainerListItem
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
fun MainView(navigation: MainViewNavigation, viewModel: MainViewModel = viewModel()) {
  LoadingIndicator.Wrap {
    Scaffold(contentWindowInsets = WindowInsets.Companion.statusBars) { paddingInsets ->
      Column(
          modifier = Modifier.fillMaxWidth().padding(paddingInsets),
          verticalArrangement = Arrangement.Center) {
            val state = viewModel.ipnState.collectAsState(initial = Ipn.State.NoState)
            val user = viewModel.loggedInUser.collectAsState(initial = null)
            val stateVal = viewModel.stateRes.collectAsState(initial = R.string.placeholder)
            val stateStr = stringResource(id = stateVal.value)
            val username = viewModel.userName

            ListItem(
                colors = MaterialTheme.colorScheme.surfaceContainerListItem,
                leadingContent = {
                  val isOn = viewModel.vpnToggleState.collectAsState(initial = false)
                  TintedSwitch(
                      onCheckedChange = { viewModel.toggleVpn() },
                      checked = isOn.value,
                      enabled = state.value != Ipn.State.NoState)
                },
                headlineContent = {
                  if (username.isNotEmpty()) {
                    Text(
                        text = username,
                        style = MaterialTheme.typography.titleMedium.copy(lineHeight = 20.sp))
                  }
                },
                supportingContent = {
                  if (username.isNotEmpty()) {
                    Text(
                        text = stateStr,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp))
                  } else {
                    Text(
                        text = stateStr,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp))
                  }
                },
                trailingContent = {
                  Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    when (user.value) {
                      null -> SettingsButton(user.value) { navigation.onNavigateToSettings() }
                      else ->
                          Avatar(profile = user.value, size = 36) {
                            navigation.onNavigateToSettings()
                          }
                    }
                  }
                })

            when (state.value) {
              Ipn.State.Running -> {

                PromptPermissionsIfNecessary(permissions = Permissions.all)

                ExitNodeStatus(navAction = navigation.onNavigateToExitNodes, viewModel = viewModel)

                PeerList(
                    viewModel = viewModel,
                    onNavigateToPeerDetails = navigation.onNavigateToPeerDetails,
                    onSearch = { viewModel.searchPeers(it) })
              }
              Ipn.State.NoState,
              Ipn.State.Starting -> StartingView()
              else ->
                  ConnectView(
                      state.value, user.value, { viewModel.toggleVpn() }, { viewModel.login {} })
            }
          }
    }
  }
}

@Composable
fun ExitNodeStatus(navAction: () -> Unit, viewModel: MainViewModel) {
  val prefs = viewModel.prefs.collectAsState()
  val netmap = viewModel.netmap.collectAsState()
  val exitNodeId = prefs.value?.ExitNodeID
  val peer = exitNodeId?.let { id -> netmap.value?.Peers?.find { it.StableID == id } }
  val location = peer?.Hostinfo?.Location
  val name = peer?.ComputedName
  val active = peer != null

  Box(modifier = Modifier.background(color = MaterialTheme.colorScheme.surfaceContainer)) {
    Box(
        modifier =
            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
                .clip(shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 10.dp))
                .fillMaxWidth()) {
          ListItem(
              modifier = Modifier.clickable { navAction() },
              colors =
                  if (active) MaterialTheme.colorScheme.primaryListItem
                  else ListItemDefaults.colors(),
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
                          location?.let { "${it.CountryCode?.flag()} ${it.Country} - ${it.City}" }
                              ?: name
                              ?: stringResource(id = R.string.none),
                      style = MaterialTheme.typography.bodyMedium,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis)
                  Icon(
                      imageVector = Icons.Outlined.ArrowDropDown,
                      contentDescription = null,
                      tint =
                          if (active) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                          else MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              },
              trailingContent = {
                if (peer != null) {
                  Button(
                      colors = MaterialTheme.colorScheme.secondaryButton,
                      onClick = { viewModel.disableExitNode() }) {
                        Text(stringResource(R.string.disable))
                      }
                }
              })
        }
  }
}

@Composable
fun SettingsButton(user: IpnLocal.LoginProfile?, action: () -> Unit) {
  // (jonathan) TODO: On iOS this is the users avatar or a letter avatar.

  IconButton(modifier = Modifier.size(24.dp), onClick = { action() }) {
    Icon(
        Icons.Outlined.Settings,
        null,
    )
  }
}

@Composable
fun StartingView() {
  Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally) {
        TailscaleLogoView(animated = true, Modifier.size(40.dp))
      }
}

@Composable
fun ConnectView(
    state: Ipn.State,
    user: IpnLocal.LoginProfile?,
    connectAction: () -> Unit,
    loginAction: () -> Unit
) {
  Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
      Column(
          modifier = Modifier.padding(8.dp).fillMaxWidth(0.7f).fillMaxHeight(),
          verticalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.CenterVertically),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        if (state != Ipn.State.NeedsLogin && user != null && !user.isEmpty()) {
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
  val netmap = viewModel.netmap.collectAsState()

  SearchBar(
      query = searchTermStr,
      onQueryChange = onSearch,
      onSearch = onSearch,
      active = true,
      onActiveChange = {},
      shape = RoundedCornerShape(10.dp),
      leadingIcon = { Icon(Icons.Outlined.Search, null) },
      trailingIcon = {
        if (searchTermStr.isNotEmpty()) ClearButton({ onSearch("") }) else CloseButton()
      },
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
      colors =
          SearchBarDefaults.colors(
              containerColor = MaterialTheme.colorScheme.surface,
              dividerColor = MaterialTheme.colorScheme.outline),
      modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
          var first = true
          peerList.value.forEach { peerSet ->
            if (!first) {
              item(key = "spacer_${peerSet.user?.DisplayName}") {
                Lists.ItemDivider()
                Spacer(Modifier.height(24.dp))
              }
            }
            first = false

            stickyHeader {
              ListItem(
                  modifier = Modifier.heightIn(max = 48.dp),
                  headlineContent = {
                    Text(
                        text =
                            peerSet.user?.DisplayName ?: stringResource(id = R.string.unknown_user),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                  })
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
                      Text(text = peer.ComputedName, style = MaterialTheme.typography.titleMedium)
                    }
                  },
                  supportingContent = {
                    Text(
                        text = peer.Addresses?.first()?.split("/")?.first() ?: "",
                        style = MaterialTheme.typography.bodyMedium)
                  })
            }
          }
        }
      }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PromptPermissionsIfNecessary(permissions: List<Permission>) {
  permissions.forEach { permission ->
    val state = rememberPermissionState(permission.name)
    if (!state.status.isGranted && !state.status.shouldShowRationale) {
      // We don't have the permission and can ask for it
      ErrorDialog(title = permission.title, message = permission.description) {
        state.launchPermissionRequest()
      }
    }
  }
}
