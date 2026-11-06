// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.components.Header
import com.tailscale.ipn.ui.components.ListItemExitNode
import com.tailscale.ipn.ui.dataSource.ExitNodePickerDataSource
import com.tailscale.ipn.ui.model.ExitNode
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.AndroidTVUtil
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.flag
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.ExitNodePickerNav
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModel
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModelFactory
import kotlin.String
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun MullvadExitNodePickerView(
    countryCode: String,
    exitNodePickerNav: ExitNodePickerNav,
    dataSource: ExitNodePickerDataSource,
) {
  val factory = remember(dataSource) { ExitNodePickerViewModelFactory(dataSource) }
  val exitNodePickerViewModel: ExitNodePickerViewModel = viewModel(factory = factory)

  val mullvadExitNodes by
      exitNodePickerViewModel.mullvadExitNodesByCountryCode.collectAsStateWithLifecycle()
  val bestAvailableByCountry by
      exitNodePickerViewModel.mullvadBestAvailableByCountry.collectAsStateWithLifecycle()
  val isRunningExitNode by exitNodePickerViewModel.isRunningExitNode.collectAsStateWithLifecycle()
  val forcedExitNodeId by MDMSettings.exitNodeID.flow.collectAsStateWithLifecycle()
  val actionScope = rememberCoroutineScope()

  mullvadExitNodes[countryCode]?.toList()?.let { nodes ->
    LoadingIndicator.Wrap {
      MullvadExitNodePickerScreen(
          state =
              MullvadExitNodePickerUiState(
                  countryCode = countryCode,
                  first = nodes.first(),
                  best = bestAvailableByCountry[countryCode]!!,
                  nodes = nodes,
                  forcedExitNodeId = forcedExitNodeId.value,
                  isRunningExitNode = isRunningExitNode,
              ),
          setExitNode = { node ->
            actionScope.launch {
              LoadingIndicator.start()
              try {
                exitNodePickerViewModel.setExitNode(node)
              } finally {
                LoadingIndicator.stop()
              }
              exitNodePickerNav.onNavigateBackHome()
            }
          },
          nav = exitNodePickerNav,
          isTv = AndroidTVUtil.isAndroidTV(),
      )
    }
  }
}

private data class MullvadExitNodePickerUiState(
    val countryCode: String,
    val first: ExitNode,
    val best: ExitNode,
    val nodes: List<ExitNode>,
    val forcedExitNodeId: String?,
    val isRunningExitNode: Boolean,
)

@Composable
private fun MullvadExitNodePickerScreen(
    state: MullvadExitNodePickerUiState,
    setExitNode: (ExitNode) -> Unit,
    nav: ExitNodePickerNav,
    isTv: Boolean,
) {
  Scaffold(
      topBar = {
        Header(
            title = { Text("${state.countryCode.flag()} ${state.first.country}") },
            onBack = nav.onNavigateBackToMullvad,
            isTv = isTv,
        )
      }
  ) { innerPadding ->
    LazyColumn(modifier = Modifier.padding(innerPadding)) {
      if (state.nodes.size > 1) {
        item {
          ListItemExitNode(
              node =
                  ExitNode(
                      id = state.best.id,
                      label = stringResource(R.string.best_available),
                      online = state.best.online,
                      selected = false,
                  ),
              forcedExitNodeId = state.forcedExitNodeId,
              isRunningExitNode = state.isRunningExitNode,
              setExitNode = setExitNode,
          )
          Lists.SectionDivider()
        }
      }

      itemsWithDividers(state.nodes) { node ->
        ListItemExitNode(
            node = node,
            forcedExitNodeId = state.forcedExitNodeId,
            isRunningExitNode = state.isRunningExitNode,
            setExitNode = setExitNode,
        )
      }
    }
  }
}

@Preview(showSystemUi = true)
@Composable
private fun MullvadExitNodePickerPreview() {
  val node1 =
      ExitNode(
          id = "1",
          label = "Exit1",
          online = MutableStateFlow(true),
          selected = true,
          mullvad = true,
          priority = 0,
          countryCode = "CA",
          country = "Canada",
          city = "Vancouver",
      )

  val node2 =
      ExitNode(
          id = "2",
          label = "Exit2",
          online = MutableStateFlow(true),
          selected = false,
          mullvad = false,
          priority = 0,
          countryCode = "CA",
          country = "Canada",
          city = "Calgary",
      )

  AppTheme {
    MullvadExitNodePickerScreen(
        state =
            MullvadExitNodePickerUiState(
                countryCode = "CA",
                first = node1,
                best = node2,
                nodes = listOf(node1, node2),
                forcedExitNodeId = "1",
                isRunningExitNode = true,
            ),
        setExitNode = {},
        nav =
            ExitNodePickerNav(
                onNavigateBackHome = {},
                onNavigateBackToExitNodes = {},
                onNavigateToMullvad = {},
                onNavigateToMullvadInfo = {},
                onNavigateBackToMullvad = {},
                onNavigateToMullvadCountry = {},
                onNavigateToRunAsExitNode = {},
            ),
        isTv = false,
    )
  }
}
