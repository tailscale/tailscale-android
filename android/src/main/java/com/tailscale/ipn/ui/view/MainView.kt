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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.theme.ts_color_light_green
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.PrimaryActionButton
import com.tailscale.ipn.ui.viewModel.MainViewModel
import kotlinx.coroutines.flow.StateFlow


// Navigation actions for the MainView
data class MainViewNavigation(
        val onNavigateToSettings: () -> Unit,
        val onNavigateToPeerDetails: (Tailcfg.Node) -> Unit,
        val onNavigateToExitNodes: () -> Unit)


@Composable
fun MainView(viewModel: MainViewModel, navigation: MainViewNavigation) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Center
        ) {
            val state = viewModel.ipnState.collectAsState(initial = Ipn.State.NoState)
            val user = viewModel.loggedInUser.collectAsState(initial = null)

            Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                val isOn = viewModel.vpnToggleState.collectAsState(initial = false)

                Switch(onCheckedChange = { viewModel.toggleVpn() }, checked = isOn.value)
                Spacer(Modifier.size(3.dp))
                StateDisplay(viewModel.stateRes, viewModel.userName)
                Box(modifier = Modifier
                        .weight(1f)
                        .clickable { navigation.onNavigateToSettings() }, contentAlignment = Alignment.CenterEnd) {
                    Avatar(profile = user.value, size = 36)
                }
            }


            when (state.value) {
                Ipn.State.Running -> {
                    ExitNodeStatus(navAction = navigation.onNavigateToExitNodes, stringResource(id = R.string.none))
                    PeerList(
                            searchTerm = viewModel.searchTerm,
                            state = viewModel.ipnState,
                            peers = viewModel.peers,
                            selfPeer = viewModel.selfPeerId,
                            onNavigateToPeerDetails = navigation.onNavigateToPeerDetails,
                            onSearch = { viewModel.searchPeers(it) })
                }

                Ipn.State.Starting -> StartingView()
                else ->
                    ConnectView(
                            user.value,
                            { viewModel.toggleVpn() },
                            { viewModel.login() }

                    )
            }
        }
    }
}

@Composable
fun ExitNodeStatus(navAction: () -> Unit, exitNode: String = stringResource(id = R.string.none)) {
    Box(modifier = Modifier
            .clickable { navAction() }
            .padding(horizontal = 8.dp)
            .clip(shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 10.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .fillMaxWidth()) {
        Column(modifier = Modifier.padding(6.dp)) {
            Text(text = stringResource(id = R.string.exit_node), style = MaterialTheme.typography.titleMedium)
            Row {
                Text(text = exitNode, style = MaterialTheme.typography.bodyMedium)
                Icon(
                        Icons.Outlined.ArrowDropDown,
                        null,
                )
            }
        }
    }
}

@Composable
fun StateDisplay(state: StateFlow<Int>, tailnet: String) {
    val stateVal = state.collectAsState(initial = R.string.placeholder)
    val stateStr = stringResource(id = stateVal.value)

    Column(modifier = Modifier.padding(7.dp)) {
        Text(text = tailnet, style = MaterialTheme.typography.titleMedium)
        Text(text = stateStr, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
fun SettingsButton(user: IpnLocal.LoginProfile?, action: () -> Unit) {
    // (jonathan) TODO: On iOS this is the users avatar or a letter avatar.
    IconButton(
            modifier = Modifier.size(24.dp),
            onClick = { action() }
    ) {
        Icon(
                Icons.Outlined.Settings,
                null,
        )
    }
}

@Composable
fun StartingView() {
    // (jonathan) TODO: On iOS this is the game-of-life Tailscale animation.
    Column(
            modifier =
            Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = stringResource(id = R.string.starting),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun ConnectView(user: IpnLocal.LoginProfile?, connectAction: () -> Unit, loginAction: () -> Unit) {
    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(8.dp)
                .fillMaxWidth(0.7f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(
                8.dp,
                alignment = Alignment.CenterVertically
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (user != null && !user.isEmpty()) {
                Icon(
                    painter = painterResource(id = R.drawable.power),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = stringResource(id = R.string.not_connected),
                    fontSize = MaterialTheme.typography.titleMedium.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    fontFamily = MaterialTheme.typography.titleMedium.fontFamily
                )
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
                        fontSize = MaterialTheme.typography.titleMedium.fontSize
                    )
                }
            } else {
                TailscaleLogoView(Modifier.size(50.dp))
                Spacer(modifier = Modifier.size(1.dp))
                Text(
                    text = stringResource(id = R.string.welcome_to_tailscale),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Text(
                    stringResource(R.string.login_to_join_your_tailnet),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.size(1.dp))
                PrimaryActionButton(onClick = loginAction) {
                    Text(
                        text = stringResource(id = R.string.log_in),
                        fontSize = MaterialTheme.typography.titleMedium.fontSize
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerList(searchTerm: StateFlow<String>,
             peers: StateFlow<List<PeerSet>>,
             state: StateFlow<Ipn.State>,
             selfPeer: StableNodeID,
             onNavigateToPeerDetails: (Tailcfg.Node) -> Unit,
             onSearch: (String) -> Unit) {
    val peerList = peers.collectAsState(initial = emptyList<PeerSet>())
    var searching = false
    val searchTermStr by searchTerm.collectAsState(initial = "")
    val stateVal = state.collectAsState(initial = Ipn.State.NoState)

    SearchBar(
        query = searchTermStr,
        onQueryChange = onSearch,
        onSearch = onSearch,
        active = true,
        onActiveChange = { searching = it },
        shape = RoundedCornerShape(10.dp),
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        colors = SearchBarDefaults.colors(),
        modifier = Modifier.fillMaxWidth()
    ) {

        LazyColumn(
                modifier =
                Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            peerList.value.forEach { peerSet ->
                item {
                    ListItem(headlineContent = {
                        Text(text = peerSet.user?.DisplayName
                                ?: stringResource(id = R.string.unknown_user), style = MaterialTheme.typography.titleLarge)
                    })
                }
                peerSet.peers.forEach { peer ->
                    item {
                        ListItem(
                                modifier = Modifier.clickable {
                                    onNavigateToPeerDetails(peer)
                                },
                                headlineContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // By definition, SelfPeer is online since we will not show the peer list unless you're connected.
                                        val isSelfAndRunning = (peer.StableID == selfPeer && stateVal.value == Ipn.State.Running)
                                        val color: Color = if ((peer.Online == true) || isSelfAndRunning) {
                                            ts_color_light_green
                                        } else {
                                            Color.Gray
                                        }
                                        Box(modifier = Modifier
                                                .size(8.dp)
                                                .background(color = color, shape = RoundedCornerShape(percent = 50))) {}
                                        Spacer(modifier = Modifier.size(8.dp))
                                        Text(text = peer.ComputedName, style = MaterialTheme.typography.titleMedium)
                                    }
                                },
                                supportingContent = {
                                    Text(
                                            text = peer.Addresses?.first()?.split("/")?.first()
                                                    ?: "",
                                            style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                trailingContent = {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                                }
                        )
                    }
                }
            }
        }
    }
}
