// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.app.Activity
import android.os.Build
import android.util.Log
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.viewModel.MainViewModel
import kotlinx.coroutines.delay

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchView(
    viewModel: MainViewModel,
    navController: NavController,
    onNavigateBack: () -> Unit,
    autoFocus: Boolean // Pass true if coming from the main view, false otherwise.
) {
  // Use TextFieldValue to preserve text and cursor position.
  var searchFieldValue by
      rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
  val searchTerm = searchFieldValue.text

  val filteredPeers by viewModel.searchViewPeers.collectAsState()
  val netmap by viewModel.netmap.collectAsState()
  val keyboardController = LocalSoftwareKeyboardController.current
  val focusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current
  var expanded by rememberSaveable { mutableStateOf(true) }
  val context = LocalContext.current as Activity
  val listState = rememberLazyListState()

  val noResultsBackground =
      if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.surface // color for dark mode
      } else {
        MaterialTheme.colorScheme.surfaceContainer // color for light mode
      }

  val callback = OnBackInvokedCallback {
    focusManager.clearFocus(force = true)
    keyboardController?.hide()
    onNavigateBack()
    viewModel.updateSearchTerm("")
  }

  DisposableEffect(Unit) {
    val dispatcher = context.onBackInvokedDispatcher
    dispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
    onDispose { dispatcher?.unregisterOnBackInvokedCallback(callback) }
  }

  LaunchedEffect(searchTerm, filteredPeers) {
    if (searchTerm.isEmpty() && filteredPeers.isNotEmpty()) {
      delay(100) // Give Compose time to update list
      listState.scrollToItem(0)
    }
  }

  // Use the autoFocus parameter to decide if we request focus when entering.
  LaunchedEffect(autoFocus) {
    if (autoFocus) {
      delay(300) // Delay to ensure UI is fully composed
      focusRequester.requestFocus()
      keyboardController?.show()
    }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)) {
      SearchBar(
          modifier = Modifier.fillMaxWidth(),
          query = searchTerm,
          onQueryChange = { newQuery ->
            // Create a new TextFieldValue with updated text and set cursor to the end.
            searchFieldValue = TextFieldValue(newQuery, selection = TextRange(newQuery.length))
            viewModel.updateSearchTerm(newQuery)
            expanded = true
          },
          onSearch = { newQuery ->
            searchFieldValue = TextFieldValue(newQuery, selection = TextRange(newQuery.length))
            viewModel.updateSearchTerm(newQuery)
            focusManager.clearFocus()
            keyboardController?.hide()
          },
          placeholder = { Text(text = stringResource(R.string.search)) },
          leadingIcon = {
            IconButton(
                onClick = {
                  focusManager.clearFocus()
                  onNavigateBack()
                  viewModel.updateSearchTerm("")
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
                    searchFieldValue = TextFieldValue("", selection = TextRange(0))
                    viewModel.updateSearchTerm("")
                    focusManager.clearFocus()
                    keyboardController?.hide()
                  }) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = stringResource(R.string.clear_search))
                  }
            }
          },
          active = expanded,
          onActiveChange = { expanded = it },
          content = {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
              if (filteredPeers.isEmpty()) {
                // When there are no filtered peers, show a "No results" message.
                item {
                  Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Lists.LargeTitle(
                        stringResource(id = R.string.no_results),
                        bottomPadding = 8.dp,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Light,
                        backgroundColor = noResultsBackground,
                        fontColor = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                }
              } else {
                var firstGroup = true
                filteredPeers.forEach { peerSet ->
                  if (!firstGroup) {
                    item { Lists.ItemDivider() }
                  }
                  firstGroup = false

                  val userName = peerSet.user?.DisplayName ?: "Unknown User"
                  peerSet.peers.forEachIndexed { index, peer ->
                    if (index > 0) {
                      item(key = "divider_${peer.StableID}") { Lists.ItemDivider() }
                    }
                    item(key = "peer_${peer.StableID}") {
                      ListItem(
                          colors = MaterialTheme.colorScheme.listItem,
                          headlineContent = {
                            Column {
                              Row(verticalAlignment = Alignment.CenterVertically) {
                                val onlineColor = peer.connectedColor(netmap)
                                Box(
                                    modifier =
                                        Modifier.size(10.dp)
                                            .background(onlineColor, RoundedCornerShape(50)))
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(peer.displayName ?: "Unknown Device")
                              }
                            }
                          },
                          supportingContent = {
                            Column {
                              Text(userName)
                              Text(peer.Addresses?.firstOrNull()?.split("/")?.first() ?: "No IP")
                            }
                          },
                          modifier =
                              Modifier.fillMaxWidth()
                                  .padding(horizontal = 4.dp, vertical = 0.dp)
                                  .clickable {
                                    viewModel.disableSearchAutoFocus()
                                    navController.navigate("peerDetails/${peer.StableID}")
                                  })
                    }
                  }
                }
              }
            }
          })
    }
  }
}
