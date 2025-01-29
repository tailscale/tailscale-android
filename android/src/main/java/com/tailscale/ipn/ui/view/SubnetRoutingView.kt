// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links.SUBNET_ROUTERS_KB_URL
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.SubnetRoutingViewModel

@Composable
fun SubnetRoutingView(backToSettings: BackNavigation, model: SubnetRoutingViewModel = viewModel()) {
    val subnetRoutes by model.advertisedRoutes.collectAsState()
    val uriHandler = LocalUriHandler.current
    val isPresentingDialog by model.isPresentingDialog.collectAsState()
    val useSubnets by model.routeAll.collectAsState()
    val currentError by model.currentError.collectAsState()

    Scaffold(topBar = {
        Header(R.string.subnet_routes, onBack = backToSettings, actions = {
            IconButton(onClick = {
                uriHandler.openUri(SUBNET_ROUTERS_KB_URL)
            }) {
                Icon(
                    painter = painterResource(R.drawable.info), contentDescription = stringResource(
                        R.string.open_kb_article
                    )
                )
            }
        })
    }) { innerPadding ->
        LoadingIndicator.Wrap {
            LazyColumn(modifier = Modifier.padding(innerPadding)) {
                currentError?.let {
                    item("error") {
                        ErrorDialog(title = R.string.failed_to_save, message = it, onDismiss = {
                            model.onErrorDismissed()
                        })
                    }
                }
                item("subnetsToggle") {
                    Setting.Switch(R.string.use_tailscale_subnets, isOn = useSubnets, onToggle = {
                        LoadingIndicator.start()
                        model.toggleUseSubnets { LoadingIndicator.stop() }
                    })
                }
                item("subtitle") {
                    ListItem(headlineContent = {
                        Text(
                            stringResource(R.string.use_tailscale_subnets_subtitle),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    })
                }
                item("divider0") {
                    Lists.SectionDivider()
                }
                item(key = "header") {
                    Lists.MutedHeader(stringResource(R.string.advertised_routes))
                    ListItem(headlineContent = {
                        Text(
                            stringResource(R.string.run_as_subnet_router_header),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    })
                }

                itemsWithDividers(subnetRoutes, key = { it }) {
                    SubnetRouteRowView(route = it, onEdit = {
                        model.startEditingRoute(it)
                    }, onDelete = {
                        model.deleteRoute(it)
                    }, modifier = Modifier.animateItem())
                }

                item("addNewRoute") {
                    Lists.ItemDivider()
                    ListItem(headlineContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Text(stringResource(R.string.add_new_route))
                        }
                    }, modifier = Modifier.clickable { model.startEditingRoute("") })
                }
            }
        }
    }

    if (isPresentingDialog) {
        Dialog(onDismissRequest = {
            model.isPresentingDialog.set(false)
        }) {
            Card {
                EditSubnetRouteDialogView(valueFlow = model.dialogTextFieldValue,
                    isValueValidFlow = model.isTextFieldValueValid,
                    onValueChange = {
                        model.dialogTextFieldValue.set(it)
                    },
                    onCommit = {
                        model.doneEditingRoute(newValue = it)
                    },
                    onCancel = {
                        model.stopEditingRoute()
                    })
            }
        }
    }
}