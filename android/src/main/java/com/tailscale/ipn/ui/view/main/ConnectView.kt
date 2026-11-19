// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view.main

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.theme.disabled
import com.tailscale.ipn.ui.view.PrimaryActionButton
import com.tailscale.ipn.ui.view.TailscaleLogoView
import com.tailscale.ipn.ui.viewModel.MainViewModel

@Composable
internal fun ConnectView(
    viewModel: MainViewModel,
    loginAtUrl: (String) -> Unit,
) {
  val state by viewModel.ipnState.collectAsState()
  val isPrepared by viewModel.isVpnPrepared.collectAsState()
  val user by viewModel.loggedInUser.collectAsState()
  val isOn by viewModel.vpnToggleState.collectAsState()
  val netmap by viewModel.netmap.collectAsState()
  // If Tailscale is stopping, don't automatically restart; wait for user to take
  // action (eg, if the user connected to another VPN).
  val shouldStartAutomatically = state != Ipn.State.Stopping

  LaunchedEffect(isPrepared) {
    if (!isPrepared && shouldStartAutomatically) {
      viewModel.showVPNPermissionLauncherIfUnauthorized()
    }
  }

  ConnectView(
      state = state,
      isPrepared = isPrepared,
      user = user,
      connectAction = { viewModel.toggleVpn(desiredState = !isOn) },
      loginAction = { viewModel.login() },
      loginAtUrlAction = loginAtUrl,
      selfNode = netmap?.SelfNode,
  )
}

@Composable
private fun ConnectView(
    state: Ipn.State,
    isPrepared: Boolean,
    user: IpnLocal.LoginProfile?,
    connectAction: () -> Unit,
    loginAction: () -> Unit,
    loginAtUrlAction: (String) -> Unit,
    selfNode: Tailcfg.Node?,
) {
  Row(
      horizontalArrangement = Arrangement.Center,
      modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
          modifier = Modifier.padding(8.dp).fillMaxWidth(0.7f).fillMaxHeight(),
          verticalArrangement =
              Arrangement.spacedBy(
                  8.dp,
                  alignment = Alignment.CenterVertically,
              ),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        if (!isPrepared) {
          TailscaleLogoView(modifier = Modifier.size(50.dp))
          Spacer(modifier = Modifier.size(1.dp))
          Text(
              text = stringResource(id = R.string.welcome_to_tailscale),
              style = MaterialTheme.typography.titleMedium,
              textAlign = TextAlign.Center,
          )
          Text(
              stringResource(R.string.give_permissions),
              style = MaterialTheme.typography.titleSmall,
              textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.size(1.dp))
          PrimaryActionButton(onClick = connectAction) {
            Text(
                text = stringResource(id = R.string.connect),
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
            )
          }
        } else if (state == Ipn.State.NeedsMachineAuth) {
          Icon(
              modifier = Modifier.size(40.dp),
              imageVector = Icons.Outlined.Lock,
              contentDescription = "Device requires authentication",
          )
          Text(
              text = stringResource(id = R.string.machine_auth_required),
              style = MaterialTheme.typography.titleMedium,
              textAlign = TextAlign.Center,
          )
          Text(
              text = stringResource(id = R.string.machine_auth_explainer),
              style = MaterialTheme.typography.bodyMedium,
              textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.size(1.dp))

          selfNode?.let {
            PrimaryActionButton(onClick = { loginAtUrlAction(it.nodeAdminUrl) }) {
              Text(
                  text = stringResource(id = R.string.open_admin_console),
                  fontSize = MaterialTheme.typography.titleMedium.fontSize,
              )
            }
          }
        } else if (state != Ipn.State.NeedsLogin && user != null && !user.isEmpty()) {
          Icon(
              painter = painterResource(id = R.drawable.power),
              contentDescription = null,
              modifier = Modifier.size(40.dp),
              tint = MaterialTheme.colorScheme.disabled,
          )
          Text(
              text = stringResource(id = R.string.not_connected),
              fontSize = MaterialTheme.typography.titleMedium.fontSize,
              fontWeight = FontWeight.SemiBold,
              textAlign = TextAlign.Center,
              fontFamily = MaterialTheme.typography.titleMedium.fontFamily,
          )
          Text(
              buildAnnotatedString {
                append(stringResource(id = R.string.connect_to_tailnet_prefix))
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(user.NetworkProfile?.tailnetNameForDisplay() ?: "")
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
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
            )
          }
        } else {
          TailscaleLogoView(modifier = Modifier.size(50.dp))
          Spacer(modifier = Modifier.size(1.dp))
          Text(
              text = stringResource(id = R.string.welcome_to_tailscale),
              style = MaterialTheme.typography.titleMedium,
              textAlign = TextAlign.Center,
          )
          Text(
              stringResource(R.string.login_to_join_your_tailnet),
              style = MaterialTheme.typography.titleSmall,
              textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.size(1.dp))
          PrimaryActionButton(onClick = loginAction) {
            Text(
                text = stringResource(id = R.string.log_in),
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
            )
          }
        }
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun ConnectViewPreview() {
  var isPrepared by remember { mutableStateOf(false) }
  var showUser by remember { mutableStateOf(false) }
  var showNode by remember { mutableStateOf(false) }
  var showState by remember { mutableStateOf(false) }
  var state by remember { mutableStateOf(Ipn.State.NoState) }
  val user =
      IpnLocal.LoginProfile(
          ID = "id",
          Name = "name",
          Key = "key",
          UserProfile = Tailcfg.UserProfile(ID = -1),
          NetworkProfile = null,
          LocalUserID = "id",
          ControlURL = null,
      )

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
      Button(onClick = { isPrepared = !isPrepared }) { Text("Prepared: $isPrepared") }
      Button(onClick = { showState = true }) {
        Text("State: $state")
        DropdownMenu(expanded = showState, onDismissRequest = { showState = false }) {
          Ipn.State.entries.forEach { entry ->
            DropdownMenuItem(text = { Text("$entry") }, onClick = { state = entry })
          }
        }
      }
      Button(onClick = { showUser = !showUser }) { Text("User: $showUser") }
      Button(onClick = { showNode = !showNode }) { Text("Node: $showNode") }
    }

    ConnectView(
        state = state,
        isPrepared = isPrepared,
        user = if (showUser) user else null,
        connectAction = {},
        loginAction = {},
        loginAtUrlAction = {},
        selfNode = if (showNode) Tailcfg.Node() else null,
    )
  }
}
