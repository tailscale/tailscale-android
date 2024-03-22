// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.flag
import com.tailscale.ipn.ui.viewModel.ExitNodePickerNav
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModel
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExitNodePicker(
    nav: ExitNodePickerNav,
    model: ExitNodePickerViewModel = viewModel(factory = ExitNodePickerViewModelFactory(nav))
) {
  LoadingIndicator.Wrap {
    Scaffold(topBar = { Header(R.string.choose_exit_node, onBack = nav.onNavigateHome) }) {
        innerPadding ->
      val tailnetExitNodes = model.tailnetExitNodes.collectAsState()
      val mullvadExitNodes = model.mullvadExitNodesByCountryCode.collectAsState()
      val anyActive = model.anyActive.collectAsState()

      LazyColumn(modifier = Modifier.padding(innerPadding)) {
        item(key = "none") {
          ExitNodeItem(
              model,
              ExitNodePickerViewModel.ExitNode(
                  label = stringResource(R.string.none),
                  online = true,
                  selected = !anyActive.value,
              ))
        }

        item { ListHeading(stringResource(R.string.tailnet_exit_nodes)) }

        items(tailnetExitNodes.value, key = { it.id!! }) { node ->
          ExitNodeItem(model, node, indent = 16.dp)
        }

        item { ListHeading(stringResource(R.string.mullvad_exit_nodes)) }

        val sortedCountries =
            mullvadExitNodes.value.entries.toList().sortedBy {
              it.value.first().country.lowercase()
            }
        items(sortedCountries) { (countryCode, nodes) ->
          val first = nodes.first()

          // TODO(oxtoacart): the modifier on the ListItem occasionally causes a crash
          // with java.lang.ClassCastException: androidx.compose.ui.ComposedModifier cannot be cast
          // to androidx.compose.runtime.RecomposeScopeImpl
          // Wrapping it in a Box eliminates this. It appears to be some kind of
          // interaction between the LazyList and the modifier.
          Box {
            ListItem(
                modifier =
                    Modifier.padding(start = 16.dp).clickable {
                      if (nodes.size > 1) {
                        nav.onNavigateToMullvadCountry(countryCode)
                      } else {
                        model.setExitNode(first)
                      }
                    },
                headlineContent = { Text("${countryCode.flag()} ${first.country}") },
                trailingContent = {
                  val text = if (nodes.size == 1) first.city else "${nodes.size}"
                  val icon =
                      if (nodes.size > 1) Icons.AutoMirrored.Outlined.KeyboardArrowRight
                      else if (first.selected) Icons.Outlined.Check else null
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text)
                    Spacer(modifier = Modifier.width(8.dp))
                    icon?.let { Icon(it, contentDescription = stringResource(R.string.more)) }
                  }
                })
          }
        }
      }
    }
  }
}

@Composable
fun ListHeading(label: String, indent: Dp = 0.dp) {
  ListItem(
      modifier = Modifier.padding(start = indent),
      headlineContent = { Text(text = label, style = MaterialTheme.typography.titleMedium) })
}

@Composable
fun ExitNodeItem(
    viewModel: ExitNodePickerViewModel,
    node: ExitNodePickerViewModel.ExitNode,
    indent: Dp = 0.dp
) {
  Box {
    ListItem(
        modifier = Modifier.padding(start = indent).clickable { viewModel.setExitNode(node) },
        headlineContent = { Text(node.city.ifEmpty { node.label }) },
        trailingContent = {
          Row {
            if (node.selected) {
              Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.more))
            } else if (!node.online) {
              Spacer(modifier = Modifier.width(8.dp))
              Text(stringResource(R.string.offline), fontStyle = FontStyle.Italic)
            }
          }
        })
  }
}
