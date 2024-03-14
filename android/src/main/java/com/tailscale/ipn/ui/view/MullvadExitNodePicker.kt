// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause


package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.flag
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModel


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MullvadExitNodePicker(viewModel: ExitNodePickerViewModel, countryCode: String) {
    val mullvadExitNodes = viewModel.mullvadExitNodesByCountryCode.collectAsState()
    val bestAvailableByCountry = viewModel.mullvadBestAvailableByCountry.collectAsState()

    mullvadExitNodes.value[countryCode]?.toList()?.let { nodes ->
        val any = nodes.first()

        LoadingIndicator.Wrap {
            Scaffold(topBar = {
                TopAppBar(title = { Text("${countryCode.flag()} ${any.country}") })
            }) { innerPadding ->
                LazyColumn(modifier = Modifier.padding(innerPadding)) {
                    if (nodes.size > 1) {
                        val bestAvailableNode = bestAvailableByCountry.value[countryCode]!!
                        item {
                            ExitNodeItem(
                                viewModel, ExitNodePickerViewModel.ExitNode(
                                    id = bestAvailableNode.id,
                                    label = stringResource(R.string.best_available),
                                    online = bestAvailableNode.online,
                                    selected = false,
                                )
                            )
                        }
                    }

                    items(nodes) { node ->
                        ExitNodeItem(viewModel, node)
                    }
                }
            }
        }
    }
}