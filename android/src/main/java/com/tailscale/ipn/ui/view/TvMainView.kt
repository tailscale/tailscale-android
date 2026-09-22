// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.ui.view

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.listCardShape
import com.tailscale.ipn.ui.viewModel.MainViewModel
import com.tailscale.ipn.util.TSLog

// A TV sits across the room and is driven with a remote, so the home screen is laid out around
// the screen's edges rather than as a list: the account on the left, search across the top, the
// tailnet switch and logo on the right, settings at the bottom, and the tailnet itself as rows of
// cards in the middle, which is the shape of every other app on the platform.
private val cardWidth = 240.dp
private val cardHeight = 104.dp
private val searchWidth = 400.dp

/**
 * The home screen on Android TV. Takes the place of [MainView] there; the states that are not yet a
 * running tailnet render the same views as everywhere else, since logging in on a TV already has
 * its own screens.
 */
@Composable
fun TvMainView(
    loginAtUrl: (String) -> Unit,
    navigation: MainViewNavigation,
    viewModel: MainViewModel,
) {
  val state by viewModel.ipnState.collectAsState(initial = Ipn.State.NoState)
  val isPrepared by viewModel.isVpnPrepared.collectAsState(initial = true)
  val isOn by viewModel.vpnToggleState.collectAsState(initial = false)
  val user by viewModel.loggedInUser.collectAsState(initial = null)
  val netmap by viewModel.netmap.collectAsState(initial = null)

  LoadingIndicator.Wrap {
    when (state) {
      Ipn.State.Running -> {
        viewModel.maybeRequestVpnPermission()
        LaunchVpnPermissionIfNeeded(viewModel)
        PromptForMissingPermissions(viewModel)
        TvHome(viewModel = viewModel, navigation = navigation)
      }
      Ipn.State.NoState,
      Ipn.State.Starting -> StartingView()
      else ->
          ConnectView(
              state,
              isPrepared,
              state != Ipn.State.Stopping,
              user,
              { viewModel.toggleVpn(desiredState = !isOn) },
              { viewModel.login() },
              loginAtUrl,
              netmap?.SelfNode,
              { viewModel.showVPNPermissionLauncherIfUnauthorized() },
          )
    }
  }
}

@Composable
private fun TvHome(viewModel: MainViewModel, navigation: MainViewNavigation) {
  val peerList by viewModel.peers.collectAsState(initial = emptyList())
  val netmap by viewModel.netmap.collectAsState(initial = null)
  var showLogOutDialog by remember { mutableStateOf(false) }
  // The remote needs somewhere to start, and the tailnet is what the screen is for.
  val firstCard = remember { FocusRequester() }

  Column(modifier = Modifier.fillMaxSize()) {
    TvTopBar(
        viewModel = viewModel,
        onAvatarClick = { showLogOutDialog = true },
        onSearchClick = navigation.onNavigateToSearch,
        onHealthClick = navigation.onNavigateToHealth,
    )

    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      peerList.forEachIndexed { index, peerSet ->
        item(key = "shelf_${peerSet.userID}") {
          TvNodeShelf(
              peerSet = peerSet,
              netmap = netmap,
              onNodeClick = navigation.onNavigateToPeerDetails,
              focusRequester = firstCard.takeIf { index == 0 },
          )
        }
      }
    }

    TvSettingsButton(onClick = navigation.onNavigateToSettings)
  }

  LaunchedEffect(peerList.isNotEmpty()) {
    if (peerList.isNotEmpty()) {
      try {
        firstCard.requestFocus()
      } catch (e: Exception) {
        TSLog.d("TvMainView", "Focus request failed: $e")
      }
    }
  }

  if (showLogOutDialog) {
    TvLogOutDialog(viewModel = viewModel, onDismiss = { showLogOutDialog = false })
  }
}

@Composable
private fun TvTopBar(
    viewModel: MainViewModel,
    onAvatarClick: () -> Unit,
    onSearchClick: () -> Unit,
    onHealthClick: () -> Unit,
) {
  val user by viewModel.loggedInUser.collectAsState(initial = null)
  val isOn by viewModel.vpnToggleState.collectAsState(initial = false)
  val healthIcon by viewModel.healthIcon.collectAsState()
  val disableToggle by MDMSettings.forceEnabled.flow.collectAsState()

  Row(
      modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Avatar(profile = user, size = 40, action = onAvatarClick, isFocusable = true)
    Spacer(modifier = Modifier.width(16.dp))
    // The search screen is restricted to API 33 and up, as it is on a phone.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      TvSearchField(modifier = Modifier.width(searchWidth), onClick = onSearchClick)
    }
    Spacer(modifier = Modifier.weight(1f))
    healthIcon?.let {
      Spacer(modifier = Modifier.width(16.dp))
      TvIconButton(onClick = onHealthClick) {
        Icon(
            painterResource(id = it),
            contentDescription = stringResource(R.string.health_warnings),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.error,
        )
      }
    }
    Spacer(modifier = Modifier.width(24.dp))
    val canToggle = !disableToggle.value && !viewModel.isToggleInProgress.value
    TvFocusable(
        shape = CircleShape,
        onClick = { if (canToggle) viewModel.toggleVpn(!isOn) },
    ) {
      Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        // The row around it takes the focus, so the switch only draws the state.
        TintedSwitch(checked = isOn, enabled = canToggle, onCheckedChange = null)
      }
    }
    Spacer(modifier = Modifier.width(24.dp))
    TailscaleLogoView(modifier = Modifier.size(28.dp))
  }
}

/** Opens the search screen. Typing happens there, where the keyboard has the whole screen. */
@Composable
private fun TvSearchField(modifier: Modifier = Modifier, onClick: () -> Unit) {
  TvFocusable(modifier = modifier, onClick = onClick, shape = CircleShape) { focused ->
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
          imageVector = Icons.Outlined.Search,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.width(12.dp))
      Text(
          text = stringResource(R.string.search_ellipsis),
          style = MaterialTheme.typography.bodyLarge,
          color =
              if (focused) MaterialTheme.colorScheme.onSurface
              else MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
      )
    }
  }
}

@Composable
private fun TvSettingsButton(onClick: () -> Unit) {
  Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
    TvFocusable(onClick = onClick, shape = CircleShape) {
      Row(
          modifier = Modifier.height(48.dp).padding(horizontal = 20.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleMedium,
        )
      }
    }
  }
}

/** One user's nodes, as a row of cards that the remote scrolls through. */
@Composable
private fun TvNodeShelf(
    peerSet: PeerSet,
    netmap: Netmap.NetworkMap?,
    onNodeClick: (StableNodeID) -> Unit,
    focusRequester: FocusRequester?,
) {
  Column {
    Text(
        text = peerSet.user?.DisplayName ?: stringResource(id = R.string.unknown_user),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 12.dp),
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      items(peerSet.peers, key = { it.StableID }) { peer ->
        TvNodeCard(
            peer = peer,
            netmap = netmap,
            onClick = { onNodeClick(peer.StableID) },
            modifier =
                if (focusRequester != null && peer.StableID == peerSet.peers.first().StableID) {
                  Modifier.focusRequester(focusRequester)
                } else {
                  Modifier
                },
        )
      }
    }
  }
}

@Composable
private fun TvNodeCard(
    peer: Tailcfg.Node,
    netmap: Netmap.NetworkMap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  TvFocusable(modifier = modifier.size(width = cardWidth, height = cardHeight), onClick = onClick) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier.size(10.dp)
                    .background(color = peer.connectedColor(netmap), shape = CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = peer.displayName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
          text = peer.Addresses?.first()?.split("/")?.first() ?: "",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
      )
    }
  }
}

/**
 * A surface the remote can land on. Focus is the only pointer a TV has, so it is shown the way the
 * platform shows it: the surface lifts a little and takes a border in the accent colour.
 */
@Composable
private fun TvFocusable(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = listCardShape,
    onClick: () -> Unit,
    content: @Composable (focused: Boolean) -> Unit,
) {
  var focused by remember { mutableStateOf(false) }
  val scale by animateFloatAsState(targetValue = if (focused) 1.06f else 1f, label = "focusScale")

  Box(
      modifier =
          modifier
              .scale(scale)
              .clip(shape)
              .background(
                  if (focused) MaterialTheme.colorScheme.surfaceBright
                  else MaterialTheme.colorScheme.listItem.containerColor
              )
              .border(
                  width = if (focused) 2.dp else 0.dp,
                  color =
                      if (focused) MaterialTheme.colorScheme.primary
                      else androidx.compose.ui.graphics.Color.Transparent,
                  shape = shape,
              )
              .onFocusChanged { focused = it.isFocused }
              .focusable()
              .clickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = null,
                  onClick = onClick,
              )
  ) {
    content(focused)
  }
}

@Composable
private fun TvIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
  TvFocusable(modifier = Modifier.size(48.dp), shape = CircleShape, onClick = onClick) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
  }
}

/** The avatar is the only thing on the screen that identifies the account, so it logs out. */
@Composable
private fun TvLogOutDialog(viewModel: MainViewModel, onDismiss: () -> Unit) {
  val user by viewModel.loggedInUser.collectAsState(initial = null)

  AlertDialog(
      onDismissRequest = onDismiss,
      shape = RoundedCornerShape(16.dp),
      title = { Text(text = stringResource(R.string.log_out)) },
      text = {
        Text(
            text =
                user?.UserProfile?.LoginName?.let { stringResource(R.string.log_out_confirm, it) }
                    ?: stringResource(R.string.log_out_confirm_no_account)
        )
      },
      confirmButton = {
        TextButton(
            onClick = {
              onDismiss()
              viewModel.logout()
            }
        ) {
          Text(text = stringResource(R.string.log_out))
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.cancel)) }
      },
  )
}
