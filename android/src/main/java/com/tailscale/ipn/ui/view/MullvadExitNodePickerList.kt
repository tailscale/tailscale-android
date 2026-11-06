// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import com.tailscale.ipn.ui.components.Header
import com.tailscale.ipn.ui.dataSource.ExitNodePickerDataSource
import com.tailscale.ipn.ui.model.ExitNode
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.AndroidTVUtil
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.flag
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.ExitNodePickerNav
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModel
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModelFactory
import com.tailscale.ipn.ui.viewModel.selected
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun MullvadExitNodePickerListView(
    exitNodePickerNav: ExitNodePickerNav,
    dataSource: ExitNodePickerDataSource,
) {
  val factory = remember(dataSource) { ExitNodePickerViewModelFactory(dataSource) }
  val exitNodePickerViewModel: ExitNodePickerViewModel = viewModel(factory = factory)
  val mullvadExitNodes by
      exitNodePickerViewModel.mullvadExitNodesByCountryCode.collectAsStateWithLifecycle()
  val sortedCountries =
      mullvadExitNodes.entries.toList().sortedBy { it.value.first().country.lowercase() }
  val actionScope = rememberCoroutineScope()

  LoadingIndicator.Wrap {
    MullvadExitNodePickerListScreen(
        state =
            MullvadExitNodePickerListUiState(
                mullvadExitNodesSortedByCountry = sortedCountries,
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

private data class MullvadExitNodePickerListUiState(
    val mullvadExitNodesSortedByCountry: List<Map.Entry<String, List<ExitNode>>>
)

@Composable
private fun MullvadExitNodePickerListScreen(
    state: MullvadExitNodePickerListUiState,
    setExitNode: (ExitNode) -> Unit,
    nav: ExitNodePickerNav,
    isTv: Boolean,
) {
  Scaffold(
      topBar = {
        Header(
            R.string.choose_mullvad_exit_node,
            onBack = nav.onNavigateBackToExitNodes,
            isTv = isTv,
        )
      }
  ) { innerPadding ->
    LazyColumn(modifier = Modifier.padding(innerPadding)) {
      itemsWithDividers(state.mullvadExitNodesSortedByCountry) { (countryCode, nodes) ->
        val first = nodes.first()

        // TODO(oxtoacart): the modifier on the ListItem occasionally causes a crash
        // with java.lang.ClassCastException: androidx.compose.ui.ComposedModifier cannot be
        // cast
        // to androidx.compose.runtime.RecomposeScopeImpl
        // Wrapping it in a Box eliminates this. It appears to be some kind of
        // interaction between the LazyList and the modifier.
        Box {
          ListItem(
              modifier =
                  Modifier.clickable {
                    if (nodes.size > 1) {
                      nav.onNavigateToMullvadCountry(countryCode)
                    } else {
                      setExitNode(first)
                    }
                  },
              leadingContent = {
                Text(
                    countryCode.flag(),
                    style = MaterialTheme.typography.titleLarge,
                )
              },
              headlineContent = {
                Text(first.country, style = MaterialTheme.typography.bodyMedium)
              },
              supportingContent = {
                Text(
                    if (nodes.size == 1) first.city
                    else "${nodes.size} ${stringResource(R.string.cities_available)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
              },
              trailingContent = {
                if (nodes.size > 1 && nodes.selected || first.selected) {
                  if (nodes.selected) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = stringResource(R.string.selected),
                    )
                  }
                }
              },
          )
        }
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
    MullvadExitNodePickerListScreen(
        state =
            MullvadExitNodePickerListUiState(
                mullvadExitNodesSortedByCountry =
                    mapOf("CA" to listOf(node1, node2)).entries.toList()
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
