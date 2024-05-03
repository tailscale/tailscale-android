// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.flag
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.ExitNodePickerNav
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModel
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModelFactory
import com.tailscale.ipn.ui.viewModel.selected

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MullvadExitNodePickerList(
    nav: ExitNodePickerNav,
    model: ExitNodePickerViewModel = viewModel(factory = ExitNodePickerViewModelFactory(nav))
) {
  LoadingIndicator.Wrap {
    Scaffold(
        topBar = {
          Header(R.string.choose_mullvad_exit_node, onBack = nav.onNavigateBackToExitNodes)
        }) { innerPadding ->
          val mullvadExitNodes by model.mullvadExitNodesByCountryCode.collectAsState()

          LazyColumn(modifier = Modifier.padding(innerPadding)) {
            val sortedCountries =
                mullvadExitNodes.entries.toList().sortedBy {
                  it.value.first().country.lowercase()
                }
            itemsWithDividers(sortedCountries) { (countryCode, nodes) ->
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
                            model.setExitNode(first)
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
                          style = MaterialTheme.typography.bodyMedium)
                    },
                    trailingContent = {
                      if (nodes.size > 1 && nodes.selected || first.selected) {
                        if (nodes.selected) {
                          Icon(
                              Icons.Outlined.Check,
                              contentDescription = stringResource(R.string.selected))
                        }
                      }
                    })
              }
            }
          }
        }
  }
}
