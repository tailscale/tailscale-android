// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.components.Header
import com.tailscale.ipn.ui.dataSource.PeerDetailsDataSource
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.theme.short
import com.tailscale.ipn.ui.util.AndroidTVUtil.isAndroidTV
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.PeerDetailsViewModel
import com.tailscale.ipn.ui.viewModel.PeerDetailsViewModelFactory
import com.tailscale.ipn.ui.viewModel.PingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerDetailsView(
    onNavigateBack: () -> Unit,
    nodeId: String,
    dataSource: PeerDetailsDataSource,
    pingViewModel: PingViewModel = viewModel(),
) {
  val factory =
      remember(dataSource) { PeerDetailsViewModelFactory(nodeId, pingViewModel, dataSource) }
  val peerDetailsViewModel: PeerDetailsViewModel = viewModel(factory = factory)

  val isPinging by peerDetailsViewModel.isPinging.collectAsStateWithLifecycle()
  val favorites by peerDetailsViewModel.favorites.collectAsStateWithLifecycle()
  val isWriting by peerDetailsViewModel.isWritingFavorites.collectAsStateWithLifecycle()
  val netmap by peerDetailsViewModel.netmap.collectAsStateWithLifecycle()
  val node by peerDetailsViewModel.node.collectAsStateWithLifecycle()

  val currentNetmap = netmap ?: return
  val currentNode = node ?: return

  val isPinned = favorites?.isFavoriteDevice(currentNode.StableID) == true

  PeerDetailsScreen(
      state =
          PeerDetailsUIState(
              node = currentNode,
              netmap = currentNetmap,
              isWriting = isWriting,
              isPinned = isPinned,
          ),
      startPing = { peerDetailsViewModel.startPing() },
      togglePin = { peerDetailsViewModel.togglePin() },
      onBack = onNavigateBack,
      isTv = isAndroidTV(),
  )

  if (isPinging) {
    ModalBottomSheet(onDismissRequest = { peerDetailsViewModel.onPingDismissal() }) {
      PingView(model = peerDetailsViewModel.pingViewModel)
    }
  }
}

@Composable
private fun AddressRow(address: String, type: String, isTv: Boolean) {
  val localClipboardManager = LocalClipboardManager.current

  // Android TV doesn't have a clipboard, nor any way to use the values, so visible only.
  val modifier =
      if (isTv) {
        Modifier.focusable(false)
      } else {
        Modifier.clickable { localClipboardManager.setText(AnnotatedString(address)) }
      }

  ListItem(
      modifier = modifier,
      colors = MaterialTheme.colorScheme.listItem,
      headlineContent = { Text(text = address) },
      supportingContent = { Text(text = type) },
      trailingContent = {
        // TODO: there is some overlap with other uses of clipboard, DRY
        if (!isTv) {
          Icon(painter = painterResource(id = R.drawable.clipboard), null)
        }
      },
  )
}

@Composable
private fun ValueRow(title: String, value: String) {
  ListItem(
      colors = MaterialTheme.colorScheme.listItem,
      headlineContent = { Text(text = title) },
      supportingContent = { Text(text = value) },
  )
}

private data class PeerDetailsUIState(
    val node: Tailcfg.Node,
    val netmap: Netmap.NetworkMap,
    val isWriting: Boolean,
    val isPinned: Boolean,
)

@Composable
private fun PeerDetailsScreen(
    state: PeerDetailsUIState,
    startPing: () -> Unit,
    togglePin: () -> Unit,
    onBack: () -> Unit,
    isTv: Boolean,
) {
  Scaffold(
      topBar = {
        Header(
            title = {
              Column {
                Text(
                    text = state.node.displayName,
                    style = MaterialTheme.typography.titleMedium.short,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Box(
                      modifier =
                          Modifier.size(8.dp)
                              .background(
                                  color = state.node.connectedColor(state.netmap),
                                  shape = RoundedCornerShape(percent = 50),
                              )
                  )

                  Text(
                      text = stringResource(id = state.node.connectedStrRes(state.netmap)),
                      style = MaterialTheme.typography.bodyMedium.short,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            },
            isTv = isTv,
            actions = {
              IconButton(onClick = startPing) {
                Icon(
                    painter = painterResource(R.drawable.sensors_24),
                    contentDescription = "Ping device",
                )
              }

              IconButton(enabled = !state.isWriting, onClick = togglePin) {
                Icon(
                    painterResource(if (state.isPinned) R.drawable.unpin_24 else R.drawable.pin_24),
                    contentDescription =
                        stringResource(
                            if (state.isPinned) R.string.unpin_device else R.string.pin_device
                        ),
                )
              }
            },
            onBack = onBack,
        )
      },
  ) { innerPadding ->
    LazyColumn(
        modifier = Modifier.padding(innerPadding),
    ) {
      item(key = "tailscaleAddresses") {
        Lists.MutedHeader(stringResource(R.string.tailscale_addresses))
      }

      itemsWithDividers(state.node.displayAddresses, key = { it.address }) { display ->
        AddressRow(address = display.address, type = display.typeString, isTv = isTv)
      }

      item(key = "infoDivider") { Lists.SectionDivider() }

      itemsWithDividers(state.node.info, key = { "info_${it.titleRes}" }) { info ->
        ValueRow(title = stringResource(id = info.titleRes), value = info.value.getString())
      }
    }
  }
}

@Preview(showSystemUi = true)
@Composable
private fun PeerDetailsPreview() {
  AppTheme {
    PeerDetailsScreen(
        state =
            PeerDetailsUIState(
                node =
                    Tailcfg.Node(
                        ComputedName = "My node",
                        Addresses = listOf("100.100.100.100", "::1"),
                        Hostinfo = Tailcfg.Hostinfo(OS = "Android"),
                        Name = "node.taiilscale.ts.net",
                    ),
                netmap =
                    Netmap.NetworkMap(
                        SelfNode = Tailcfg.Node(),
                        Domain = "tailscale.com",
                        TKAEnabled = false,
                        UserProfiles = emptyMap(),
                    ),
                isWriting = false,
                isPinned = true,
            ),
        startPing = {},
        togglePin = {},
        onBack = {},
        isTv = false,
    )
  }
}
