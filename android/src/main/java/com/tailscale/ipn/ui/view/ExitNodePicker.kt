// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.clickableOrGrayedOut
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.ExitNodePickerNav
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModel
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModelFactory
import com.tailscale.ipn.ui.viewModel.selected

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExitNodePicker(
    nav: ExitNodePickerNav,
    model: ExitNodePickerViewModel = viewModel(factory = ExitNodePickerViewModelFactory(nav))
) {
  LoadingIndicator.Wrap {
    Scaffold(topBar = { Header(R.string.choose_exit_node, onBack = nav.onNavigateBack) }) {
        innerPadding ->
      val tailnetExitNodes = model.tailnetExitNodes.collectAsState().value
      val mullvadExitNodesByCountryCode = model.mullvadExitNodesByCountryCode.collectAsState().value
      val mullvadExitNodeCount = model.mullvadExitNodeCount.collectAsState().value
      val anyActive = model.anyActive.collectAsState()

      LazyColumn(modifier = Modifier.padding(innerPadding)) {
        item(key = "runExitNode") {
          RunAsExitNodeItem(nav = nav, viewModel = model)
          Lists.SectionDivider()
        }

        item(key = "none") {
          ExitNodeItem(
              model,
              ExitNodePickerViewModel.ExitNode(
                  label = stringResource(R.string.none),
                  online = true,
                  selected = !anyActive.value,
              ))
        }

        item { Lists.SectionDivider() }

        itemsWithDividers(tailnetExitNodes, key = { it.id!! }) { node -> ExitNodeItem(model, node) }

        item { Lists.SectionDivider() }

        if (mullvadExitNodeCount > 0) {
          item(key = "mullvad") {
            MullvadItem(nav, mullvadExitNodeCount, mullvadExitNodesByCountryCode.selected)
          }
        }

        item("allowLANAccessToggle") { SettingRow(model.allowLANAccessSetting) }
      }
    }
  }
}

@Composable
fun ExitNodeItem(
    viewModel: ExitNodePickerViewModel,
    node: ExitNodePickerViewModel.ExitNode,
) {
  Box {
    // TODO: add disabled styling
    ListItem(
        modifier =
            Modifier.clickableOrGrayedOut(enabled = node.online) { viewModel.setExitNode(node) },
        headlineContent = { Text(node.city.ifEmpty { node.label }) },
        supportingContent = { if (!node.online) Text(stringResource(R.string.offline)) },
        trailingContent = {
          Row {
            if (node.selected) {
              Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.selected))
            }
          }
        })
  }
}

@Composable
fun MullvadItem(nav: ExitNodePickerNav, count: Int, selected: Boolean) {
  Box {
    ListItem(
        modifier = Modifier.clickable { nav.onNavigateToMullvad() },
        headlineContent = { Text(stringResource(R.string.mullvad_exit_nodes)) },
        supportingContent = { Text("$count ${stringResource(R.string.nodes_available)}") },
        trailingContent = {
          if (selected) {
            Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.selected))
          }
        })
  }
}

@Composable
fun RunAsExitNodeItem(nav: ExitNodePickerNav, viewModel: ExitNodePickerViewModel) {
  val isRunningExitNode = viewModel.isRunningExitNode.collectAsState().value

  Box {
    ListItem(
        modifier = Modifier.clickable { nav.onNavigateToRunAsExitNode() },
        headlineContent = { Text(stringResource(id = R.string.run_as_exit_node)) },
        trailingContent = {
          if (isRunningExitNode) {
            Text(stringResource(R.string.enabled))
          } else {
            Text(stringResource(R.string.disabled))
          }
        })
  }
}
