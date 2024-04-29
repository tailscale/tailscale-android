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
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.theme.disabledListItem
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.ExitNodePickerNav
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModel
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModelFactory
import com.tailscale.ipn.ui.viewModel.selected

@Composable
fun ExitNodePicker(
    nav: ExitNodePickerNav,
    model: ExitNodePickerViewModel = viewModel(factory = ExitNodePickerViewModelFactory(nav))
) {
  LoadingIndicator.Wrap {
    Scaffold(topBar = { Header(R.string.choose_exit_node, onBack = nav.onNavigateBackHome) }) {
        innerPadding ->
      val tailnetExitNodes = model.tailnetExitNodes.collectAsState().value
      val mullvadExitNodesByCountryCode = model.mullvadExitNodesByCountryCode.collectAsState().value
      val mullvadExitNodeCount = model.mullvadExitNodeCount.collectAsState().value
      val anyActive = model.anyActive.collectAsState()
      val allowLANAccess = Notifier.prefs.collectAsState().value?.ExitNodeAllowLANAccess == true

      LazyColumn(modifier = Modifier.padding(innerPadding)) {
        item(key = "header") {
          ExitNodeItem(
              model,
              ExitNodePickerViewModel.ExitNode(
                  label = stringResource(R.string.none),
                  online = true,
                  selected = !anyActive.value,
              ))
          Lists.ItemDivider()
          RunAsExitNodeItem(nav = nav, viewModel = model)
        }

        item(key = "divider1") { Lists.SectionDivider() }

        itemsWithDividers(tailnetExitNodes, key = { it.id!! }) { node -> ExitNodeItem(model, node) }

        if (mullvadExitNodeCount > 0) {
          item(key = "mullvad") {
            Lists.SectionDivider()
            MullvadItem(
                nav, mullvadExitNodesByCountryCode.size, mullvadExitNodesByCountryCode.selected)
          }
        }

        // TODO: make sure this actually works, and if not, leave it out for now
        item(key = "allowLANAccess") {
          Lists.SectionDivider()

          Setting.Switch(R.string.allow_lan_access, isOn = allowLANAccess) {
            LoadingIndicator.start()
            model.toggleAllowLANAccess { LoadingIndicator.stop() }
          }
        }
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
    var modifier: Modifier = Modifier
    if (node.online) {
      modifier = modifier.clickable { viewModel.setExitNode(node) }
    }
    ListItem(
        modifier = modifier,
        colors =
            if (node.online) MaterialTheme.colorScheme.listItem
            else MaterialTheme.colorScheme.disabledListItem,
        headlineContent = {
          Text(node.city.ifEmpty { node.label }, style = MaterialTheme.typography.bodyMedium)
        },
        supportingContent = {
          if (!node.online)
              Text(stringResource(R.string.offline), style = MaterialTheme.typography.bodyMedium)
        },
        trailingContent = {
          Row {
            if (node.selected) {
              Icon(Icons.Outlined.Check, null)
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
        headlineContent = {
          Text(
              stringResource(R.string.mullvad_exit_nodes),
              style = MaterialTheme.typography.bodyMedium)
        },
        supportingContent = {
          Text(
              "$count ${stringResource(R.string.countries)}",
              style = MaterialTheme.typography.bodyMedium)
        },
        trailingContent = {
          if (selected) {
            Icon(Icons.Outlined.Check, null)
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
        headlineContent = {
          Text(
              stringResource(id = R.string.run_as_exit_node),
              style = MaterialTheme.typography.bodyMedium)
        },
        supportingContent = {
          if (isRunningExitNode) {
            Text(stringResource(R.string.enabled))
          } else {
            Text(stringResource(R.string.disabled))
          }
        })
  }
}
