// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.viewModel.MainViewModel
import kotlinx.coroutines.flow.StateFlow


// Navigation actions for the MainView
data class MainViewNavigation(
        val onNavigateToSettings: () -> Unit,
        val onNavigateToPeerDetails: (Tailcfg.Node) -> Unit,
        val onNavigateToExitNodes: () -> Unit)


@Composable
fun MainView(viewModel: MainViewModel, navigation: MainViewNavigation) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Center
        ) {
            val state = viewModel.ipnState.collectAsState(initial = Ipn.State.NoState)
            val user = viewModel.loggedInUser.collectAsState(initial = null)

            Row(modifier = Modifier
                    .padding(6.dp)
                    .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically) {
                val isOn = viewModel.vpnToggleState.collectAsState(initial = false)

                Switch(onCheckedChange = { viewModel.toggleVpn() }, checked = isOn.value)
                StateDisplay(viewModel.stateStr, viewModel.userName)
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    SettingsButton(user.value, navigation.onNavigateToSettings)
                }
            }

            // (jonathan) TODO: Show the selected exit node name here.
            if (state.value == Ipn.State.Running) {
                ExitNodeStatus(navAction = navigation.onNavigateToExitNodes, "None")
            }

            when (state.value) {
                Ipn.State.Running -> PeerList(peers = viewModel.peers, onNavigateToPeerDetails = navigation.onNavigateToPeerDetails)
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
fun ExitNodeStatus(navAction: () -> Unit, exitNode: String = "None") {
    Box(modifier = Modifier
            .clickable { navAction() }
            .padding(12.dp)
            .clip(shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 10.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .fillMaxWidth()) {
        Column(modifier = Modifier.padding(6.dp)) {
            Text(text = "Exit Node", style = MaterialTheme.typography.titleMedium)
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
fun StateDisplay(state: StateFlow<String>, tailnet: String) {
    val stateStr = state.collectAsState(initial = "--")

    Column(modifier = Modifier.padding(6.dp)) {
        Text(text = "${tailnet}", style = MaterialTheme.typography.titleMedium)
        Text(text = "${stateStr.value}", style = MaterialTheme.typography.bodyMedium)
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
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
    ) { Text(text = "Starting...", style = MaterialTheme.typography.titleMedium) }
}

@Composable
fun ConnectView(user: IpnLocal.LoginProfile?, connectAction: () -> Unit, loginAction: () -> Unit) {
    Column(
            modifier =
            Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Not Connected", style = MaterialTheme.typography.titleMedium)
        if (user != null) {
            val tailnetName = user.NetworkProfile?.DomainName ?: ""
            Text(
                    "Connect to your ${tailnetName} tailnet",
                    style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = connectAction) { Text(text = "Connect") }
        } else {
            Button(onClick = loginAction) { Text(text = "Log In") }
        }
    }
}

@Composable
fun PeerList(peers: StateFlow<List<Tailcfg.Node>>, onNavigateToPeerDetails: (Tailcfg.Node) -> Unit) {
    val peerList = peers.collectAsState(initial = emptyList<Tailcfg.Node>())

    Column(
            modifier =
            Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.secondaryContainer),
    ) {
        peerList.value.forEach { peer ->
            ListItem(
                    modifier = Modifier.clickable {
                        onNavigateToPeerDetails(peer)
                    },
                    headlineContent = {
                        Text(text = peer.ComputedName, style = MaterialTheme.typography.titleMedium)
                    },
                    supportingContent = {
                        Text(
                                text = peer.Addresses?.first() ?: "",
                                style = MaterialTheme.typography.bodyMedium
                        )
                    }
            )
        }
    }
}