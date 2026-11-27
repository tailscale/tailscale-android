// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view.main

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Favorites
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.theme.searchBarColors
import com.tailscale.ipn.ui.util.AndroidTVUtil.isAndroidTV
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.view.MainViewNavigation
import com.tailscale.ipn.ui.viewModel.MainViewModel
import com.tailscale.ipn.util.FeatureFlags
import kotlin.String
import kotlin.collections.List
import kotlin.collections.any
import kotlin.collections.groupBy
import kotlin.collections.orEmpty

@Composable
internal fun PeerList(
    viewModel: MainViewModel,
    navigation: MainViewNavigation,
) {
  val peerList by viewModel.peers.collectAsState()
  val searchTerm by viewModel.searchTerm.collectAsState()
  val netmap by viewModel.netmap.collectAsState()
  val favorites by viewModel.favorites.collectAsState()
  val isTiramisu = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
  val isFlagEnabled = FeatureFlags.isEnabled("enable_new_search")

  PeerList(
      state =
          PeerListUIState(
              peerList = peerList,
              searchTerm = searchTerm,
              netmap = netmap,
              favorites = favorites,
              enableSearch = isTiramisu && isFlagEnabled,
          ),
      isTv = isAndroidTV(),
      onNavigateToPeerDetails = navigation.onNavigateToPeerDetails,
      onSearchBarClick = navigation.onNavigateToSearch,
      onSearch = { viewModel.updateSearchTerm(it) },
      startPing = viewModel::startPing,
      togglePin = viewModel::togglePin,
  )
}

private data class PeerListUIState(
    val peerList: List<PeerSet>,
    val searchTerm: String,
    val netmap: Netmap.NetworkMap?,
    val favorites: Favorites?,
    val enableSearch: Boolean,
)

@Composable
private fun PeerList(
    state: PeerListUIState,
    isTv: Boolean,
    onNavigateToPeerDetails: (Tailcfg.Node) -> Unit,
    onSearchBarClick: () -> Unit,
    onSearch: (String) -> Unit,
    startPing: (Tailcfg.Node) -> Unit,
    togglePin: (Tailcfg.Node) -> Unit,
) {
  val focusManager = LocalFocusManager.current
  var expandedPeer by remember { mutableStateOf<Tailcfg.Node?>(null) }
  var isSearchFocussed by remember { mutableStateOf(false) }
  var isListFocussed by remember { mutableStateOf(false) }

  val showNoResults = state.searchTerm.isNotEmpty() && state.peerList.isEmpty()
  // Restrict search to devices running API 33+ (see https://github.com/tailscale/corp/issues/27375)

  validateIds(state.peerList, state.netmap)

  Column(modifier = Modifier.fillMaxSize()) {
    if (state.enableSearch) {
      Search(onSearchBarClick = onSearchBarClick)
    } else {
      if (!isTv) {
        Box(
            modifier =
                Modifier.fillMaxWidth().background(color = MaterialTheme.colorScheme.surface),
        ) {
          OutlinedTextField(
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 0.dp)
                      .onFocusChanged { isSearchFocussed = it.isFocused },
              singleLine = true,
              shape = MaterialTheme.shapes.extraLarge,
              colors = MaterialTheme.colorScheme.searchBarColors,
              leadingIcon = {
                Icon(imageVector = Icons.Outlined.Search, contentDescription = "search")
              },
              trailingIcon = {
                if (isSearchFocussed) {
                  IconButton(
                      onClick = {
                        focusManager.clearFocus()
                        onSearch("")
                      },
                  ) {
                    Icon(
                        imageVector =
                            if (state.searchTerm.isEmpty()) Icons.Outlined.Close
                            else Icons.Outlined.Clear,
                        contentDescription = "clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              },
              placeholder = {
                Text(
                    text = stringResource(id = R.string.search),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
              },
              value = state.searchTerm,
              onValueChange = { onSearch(it) },
          )
        }
      }
    }

    // Peers display
    LazyColumn(
        modifier =
            Modifier.fillMaxWidth()
                .weight(1f) // LazyColumn gets the remaining vertical space
                .onFocusChanged { isListFocussed = it.isFocused }
                .background(color = MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
      // Handle case when no results are found
      if (showNoResults) {
        item {
          Spacer(
              Modifier.height(16.dp)
                  .fillMaxSize()
                  .focusable(false)
                  .background(color = MaterialTheme.colorScheme.surface),
          )
          Lists.LargeTitle(
              stringResource(id = R.string.no_results),
              bottomPadding = 8.dp,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Light,
          )
        }
      }

      // Iterate over peer sets to display them
      state.peerList.forEachIndexed { idx, peerSet ->
        if (idx != 0) {
          item(key = "user_divider_${peerSet.id}") { Lists.ItemDivider() }
        }
        if (isTv) {
          item { NodesSectionHeader(peerSet = peerSet, focusable = true) }
        } else {
          stickyHeader { NodesSectionHeader(peerSet = peerSet, focusable = false) }
        }
        itemsWithDividers(peerSet.nodes, key = { it.StableID }) { peer ->
          ListItem(
              modifier =
                  Modifier.combinedClickable(
                      onClick = { onNavigateToPeerDetails(peer) },
                      onLongClick = { expandedPeer = peer },
                  ),
              colors = MaterialTheme.colorScheme.listItem,
              headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Box(
                      modifier =
                          Modifier.padding(top = 2.dp)
                              .size(10.dp)
                              .background(
                                  color = peer.connectedColor(state.netmap),
                                  shape = RoundedCornerShape(percent = 50),
                              ),
                  )
                  Text(
                      text = peer.displayName,
                      style = MaterialTheme.typography.titleMedium,
                  )
                  if (expandedPeer?.StableID == peer.StableID) {
                    DeviceDropdownMenu(
                        favorites = state.favorites,
                        peer = peer,
                        netmap = state.netmap,
                        hidePeerDropdownMenu = { expandedPeer = null },
                        togglePin = togglePin,
                        startPing = startPing,
                    )
                  }
                }
              },
              supportingContent = {
                Text(
                    text = peer.Addresses?.first()?.split("/")?.first() ?: "",
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = MaterialTheme.typography.titleMedium.lineHeight,
                        ),
                )
              },
          )
        }
      }
    }
  }
}

private fun validateIds(peerList: List<PeerSet>, netmap: Netmap.NetworkMap?) {
  val renderedPeers = peerList.flatMap { peerSet ->
    peerSet.nodes.map { peer -> peerSet.id to peer }
  }
  val duplicateRenderedStableIDs =
      renderedPeers.groupBy { (_, peer) -> peer.StableID }.filterValues { it.size > 1 }
  val duplicateRenderedNodeIDs =
      renderedPeers.groupBy { (_, peer) -> peer.ID }.filterValues { it.size > 1 }

  val netmapPeers = netmap?.Peers.orEmpty()
  val self = netmap?.SelfNode
  val duplicateNetmapStableIDs = netmapPeers.groupBy { it.StableID }.filterValues { it.size > 1 }
  val duplicateNetmapNodeIDs = netmapPeers.groupBy { it.ID }.filterValues { it.size > 1 }
  val selfInPeersByStableID = self != null && netmapPeers.any { it.StableID == self.StableID }
  val selfInPeersByNodeID = self != null && netmapPeers.any { it.ID == self.ID }

  check(duplicateRenderedStableIDs.isEmpty()) {
    buildString {
      append("Duplicate StableIDs in MainView")
      append("; peerSets=${peerList.size}")
      append("; renderedPeers=${renderedPeers.size}")
      append("; netmapPeers=${netmapPeers.size}")
      append("; duplicateRenderedStableIDs=${duplicateRenderedStableIDs.keys}")
      append("; duplicateRenderedNodeIDs=${duplicateRenderedNodeIDs.keys}")
      append("; duplicateNetmapStableIDs=${duplicateNetmapStableIDs.keys}")
      append("; duplicateNetmapNodeIDs=${duplicateNetmapNodeIDs.keys}")
      append("; selfNodeID=${self?.ID}")
      append("; selfStableID=${self?.StableID}")
      append("; selfInPeersByNodeID=$selfInPeersByNodeID")
      append("; selfInPeersByStableID=$selfInPeersByStableID")
      append("; entries=")
      append(
          duplicateRenderedStableIDs.entries.joinToString("|") { (stableID, entries) ->
            "$stableID=[" +
                entries.joinToString(",") { (setUser, peer) ->
                  "nodeID=${peer.ID}" +
                      "/peerUser=${peer.User}" +
                      "/setUser=$setUser" +
                      "/self=${self?.ID == peer.ID}"
                } +
                "]"
          },
      )
    }
  }
}

@Preview
@Composable
private fun PeerListPreview() {
  PeerList(
      state =
          PeerListUIState(
              peerList = emptyList(),
              searchTerm = "",
              netmap =
                  Netmap.NetworkMap(
                      SelfNode = Tailcfg.Node(),
                      Domain = "tailscale.com",
                      UserProfiles = emptyMap(),
                      TKAEnabled = false,
                  ),
              favorites = Favorites(),
              enableSearch = true,
          ),
      isTv = false,
      onNavigateToPeerDetails = {},
      onSearchBarClick = {},
      onSearch = {},
      startPing = {},
      togglePin = {},
  )
}
