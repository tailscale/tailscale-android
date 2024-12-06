// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.viewModel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchView(viewModel: MainViewModel, navController: NavController, onNavigateBack: () -> Unit) {
  val searchTerm by viewModel.searchTerm.collectAsState()
  val filteredPeers by viewModel.peers.collectAsState()
  val netmap by viewModel.netmap.collectAsState()
  val keyboardController = LocalSoftwareKeyboardController.current
  val focusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current
  var expanded by rememberSaveable { mutableStateOf(true) }

  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
    keyboardController?.show()
  }

  Column(
      modifier =
          Modifier.fillMaxWidth().focusRequester(focusRequester).clickable {
            focusRequester.requestFocus()
            keyboardController?.show()
          }) {
        SearchBar(
            modifier = Modifier.fillMaxWidth(),
            query = searchTerm,
            onQueryChange = { query ->
              viewModel.updateSearchTerm(query)
              expanded = query.isNotEmpty()
            },
            onSearch = { query ->
              viewModel.updateSearchTerm(query)
              focusManager.clearFocus()
              keyboardController?.hide()
            },
            placeholder = { R.string.search },
            leadingIcon = {
              IconButton(
                  onClick = {
                    focusManager.clearFocus()
                    onNavigateBack()
                  }) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
            },
            trailingIcon = {
              if (searchTerm.isNotEmpty()) {
                IconButton(
                    onClick = {
                      viewModel.updateSearchTerm("")
                      focusManager.clearFocus()
                      keyboardController?.hide()
                    }) {
                      Icon(Icons.Default.Clear, stringResource(R.string.clear_search))
                    }
              }
            },
            active = expanded,
            onActiveChange = { expanded = it },
            content = {
              Column(Modifier.verticalScroll(rememberScrollState()).fillMaxSize()) {
                filteredPeers.forEach { peerSet ->
                  val userName = peerSet.user?.DisplayName ?: "Unknown User"
                  peerSet.peers.forEach { peer ->
                    val deviceName = peer.displayName ?: "Unknown Device"
                    val ipAddress = peer.Addresses?.firstOrNull()?.split("/")?.first() ?: "No IP"

                    ListItem(
                        headlineContent = { Text(userName) },
                        supportingContent = {
                          Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                              val onlineColor = peer.connectedColor(netmap)
                              Box(
                                  modifier =
                                      Modifier.size(10.dp)
                                          .background(onlineColor, shape = RoundedCornerShape(50)))
                              Spacer(modifier = Modifier.size(8.dp))
                              Text(deviceName)
                            }
                            Text(ipAddress)
                          }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier =
                            Modifier.clickable {
                                  navController.navigate("peerDetails/${peer.StableID}")
                                }
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp))
                  }
                }
              }
            })
      }
}
