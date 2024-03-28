// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.theme.ts_color_light_green
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.flag
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.MainViewModel
import kotlinx.coroutines.flow.StateFlow

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

            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 16.dp)
                        .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                  val isOn = viewModel.vpnToggleState.collectAsState(initial = false)
                  if (state.value != Ipn.State.NoState) {
                    TintedSwitch(onCheckedChange = { viewModel.toggleVpn() }, checked = isOn.value)
                    Spacer(Modifier.size(3.dp))
                  }

                  StateDisplay(viewModel.stateRes, viewModel.userName)

                  Box(
                      modifier =
                          Modifier.weight(1f).clickable { navigation.onNavigateToSettings() },
                      contentAlignment = Alignment.CenterEnd) {
                        when (user.value) {
                          null -> SettingsButton(user.value) { navigation.onNavigateToSettings() }
                          else -> Avatar(profile = user.value, size = 36)
                        }
                      }
                }

            when (state.value) {
              Ipn.State.Running -> {

                PromptPermissionsIfNecessary(permissions = Permissions.all)

                val selfPeerId = viewModel.selfPeerId.collectAsState(initial = "")
                Row(
                    modifier =
                        Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(top = 10.dp, bottom = 20.dp)) {
                      ExitNodeStatus(
                          navAction = navigation.onNavigateToExitNodes, viewModel = viewModel)
                    }
                PeerList(
                    searchTerm = viewModel.searchTerm,
                    state = viewModel.ipnState,
                    peers = viewModel.peers,
                    selfPeer = selfPeerId.value,
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
  val name = peer?.Name

  Box(
      modifier =
          Modifier.padding(horizontal = 16.dp)
              .clip(shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 10.dp))
              .background(MaterialTheme.colorScheme.background)
              .fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable { navAction() },
            headlineContent = {
              Text(
                  stringResource(R.string.exit_node),
                  style = MaterialTheme.typography.titleMedium,
              )
            },
            supportingContent = {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text =
                        location?.let { "${it.CountryCode?.flag()} ${it.Country} - ${it.City}" }
                            ?: name
                            ?: stringResource(id = R.string.none),
                    style = MaterialTheme.typography.bodyLarge)
                Icon(
                    Icons.Outlined.ArrowDropDown,
                    null,
                )
              }
            },
            trailingContent = {
              if (peer != null) {
                Button(onClick = { viewModel.disableExitNode() }) {
                  Text(stringResource(R.string.disable))
                }
              }
            })
      }
}

@Composable
fun StateDisplay(state: StateFlow<Int>, tailnet: String) {
  val stateVal = state.collectAsState(initial = R.string.placeholder)
  val stateStr = stringResource(id = stateVal.value)

  Column(modifier = Modifier.padding(7.dp)) {
    when (tailnet.isEmpty()) {
      false -> {
        Text(text = tailnet, style = MaterialTheme.typography.titleMedium)
        Text(
            text = stateStr,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary)
      }
      true -> {
        Text(
            text = stateStr,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary)
      }
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
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally) {
        TailscaleLogoView(animated = true, Modifier.size(72.dp))
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier.background(MaterialTheme.colorScheme.secondaryContainer).fillMaxWidth()) {
          Column(
              modifier = Modifier.padding(8.dp).fillMaxWidth(0.7f).fillMaxHeight(),
              verticalArrangement =
                  Arrangement.spacedBy(8.dp, alignment = Alignment.CenterVertically),
              horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            if (state != Ipn.State.NeedsLogin && user != null && !user.isEmpty()) {
              Icon(
                  painter = painterResource(id = R.drawable.power),
                  contentDescription = null,
                  modifier = Modifier.size(48.dp),
                  tint = MaterialTheme.colorScheme.secondary)
              Text(
                  text = stringResource(id = R.string.not_connected),
                  fontSize = MaterialTheme.typography.titleMedium.fontSize,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.primary,
                  textAlign = TextAlign.Center,
                  fontFamily = MaterialTheme.typography.titleMedium.fontFamily)
              val tailnetName = user.NetworkProfile?.DomainName ?: ""
              Text(
                  stringResource(id = R.string.connect_to_tailnet, tailnetName),
                  fontSize = MaterialTheme.typography.titleMedium.fontSize,
                  fontWeight = FontWeight.Normal,
                  color = MaterialTheme.colorScheme.secondary,
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
                  color = MaterialTheme.colorScheme.primary,
                  textAlign = TextAlign.Center)
              Text(
                  stringResource(R.string.login_to_join_your_tailnet),
                  style = MaterialTheme.typography.titleSmall,
                  color = MaterialTheme.colorScheme.secondary,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerList(
    searchTerm: StateFlow<String>,
    peers: StateFlow<List<PeerSet>>,
    state: StateFlow<Ipn.State>,
    selfPeer: StableNodeID,
    onNavigateToPeerDetails: (Tailcfg.Node) -> Unit,
    onSearch: (String) -> Unit
) {
  val peerList = peers.collectAsState(initial = emptyList<PeerSet>())
  val searchTermStr by searchTerm.collectAsState(initial = "")
  val stateVal = state.collectAsState(initial = Ipn.State.NoState)

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
              containerColor = Color.Transparent, dividerColor = Color.Transparent),
      modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier =
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
          peerList.value.forEach { peerSet ->
            item {
              ListItem(
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
                  headlineContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      // By definition, SelfPeer is online since we will not show the peer list
                      // unless you're connected.
                      val isSelfAndRunning =
                          (peer.StableID == selfPeer && stateVal.value == Ipn.State.Running)
                      val color: Color =
                          if ((peer.Online == true) || isSelfAndRunning) {
                            ts_color_light_green
                          } else {
                            Color.Gray
                          }
                      Box(
                          modifier =
                              Modifier.size(10.dp)
                                  .background(
                                      color = color, shape = RoundedCornerShape(percent = 50))) {}
                      Spacer(modifier = Modifier.size(6.dp))
                      Text(text = peer.ComputedName, style = MaterialTheme.typography.titleMedium)
                    }
                  },
                  supportingContent = {
                    Text(
                        text = peer.Addresses?.first()?.split("/")?.first() ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary)
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
      ErrorDialog(title = permission.title, message = permission.neededDescription) {
        state.launchPermissionRequest()
      }
    }
  }
}
