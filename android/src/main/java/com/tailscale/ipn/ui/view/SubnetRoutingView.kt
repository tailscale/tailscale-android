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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links.SUBNET_ROUTERS_KB_URL
import com.tailscale.ipn.ui.components.Header
import com.tailscale.ipn.ui.components.ListItemSwitch
import com.tailscale.ipn.ui.dataSource.SubnetRoutingDataSource
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.AndroidTVUtil
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.SubnetRoutingViewModel
import com.tailscale.ipn.ui.viewModel.SubnetRoutingViewModelFactory
import kotlin.Boolean
import kotlin.String

@Composable
fun SubnetRoutingView(
    backToSettings: () -> Unit,
    dataSource: SubnetRoutingDataSource,
) {
  val uriHandler = LocalUriHandler.current

  val factory = remember(dataSource) { SubnetRoutingViewModelFactory(dataSource) }
  val subnetRoutingViewModel: SubnetRoutingViewModel = viewModel(factory = factory)

  val subnetRoutes by subnetRoutingViewModel.advertisedRoutes.collectAsStateWithLifecycle()
  val isPresentingDialog by subnetRoutingViewModel.isPresentingDialog.collectAsStateWithLifecycle()
  val useSubnets by subnetRoutingViewModel.routeAll.collectAsStateWithLifecycle()
  val currentError by subnetRoutingViewModel.currentError.collectAsStateWithLifecycle()

  SubnetRoutingScreen(
      state =
          SubnetRoutingUIState(
              currentError = currentError,
              useSubnets = useSubnets,
              subnetRoutes = subnetRoutes,
          ),
      openSubnetRouter = { uriHandler.openUri(SUBNET_ROUTERS_KB_URL) },
      onErrorDismissed = subnetRoutingViewModel::onErrorDismissed,
      toggleUseSubnets = {
        LoadingIndicator.start()
        try {
          subnetRoutingViewModel.toggleUseSubnets()
        } finally {
          LoadingIndicator.stop()
        }
      },
      startEditingRoute = subnetRoutingViewModel::startEditingRoute,
      deleteRoute = subnetRoutingViewModel::deleteRoute,
      onBack = backToSettings,
      isTv = AndroidTVUtil.isAndroidTV(),
  )

  if (isPresentingDialog) {
    Dialog(onDismissRequest = { subnetRoutingViewModel.stopEditingRoute() }) {
      Card {
        EditSubnetRouteDialogView(
            valueFlow = subnetRoutingViewModel.dialogTextFieldValue,
            isValueValidFlow = subnetRoutingViewModel.isTextFieldValueValid,
            onValueChange = subnetRoutingViewModel::updateDialogTextFieldValue,
            onCommit = { subnetRoutingViewModel.doneEditingRoute(newValue = it) },
            onCancel = { subnetRoutingViewModel.stopEditingRoute() },
        )
      }
    }
  }
}

private data class SubnetRoutingUIState(
    val currentError: String?,
    val useSubnets: Boolean,
    val subnetRoutes: List<String>,
)

@Composable
private fun SubnetRoutingScreen(
    state: SubnetRoutingUIState,
    openSubnetRouter: () -> Unit,
    onErrorDismissed: () -> Unit,
    toggleUseSubnets: (Boolean) -> Unit,
    startEditingRoute: (String) -> Unit,
    deleteRoute: (String) -> Unit,
    onBack: () -> Unit,
    isTv: Boolean,
) {
  Scaffold(
      topBar = {
        Header(
            R.string.subnet_routes,
            onBack = onBack,
            isTv = isTv,
            actions = {
              IconButton(onClick = openSubnetRouter) {
                Icon(
                    painter = painterResource(R.drawable.info),
                    contentDescription = stringResource(R.string.open_kb_article),
                )
              }
            },
        )
      }
  ) { innerPadding ->
    LoadingIndicator.Wrap {
      LazyColumn(modifier = Modifier.padding(innerPadding)) {
        state.currentError?.let {
          item("error") {
            ErrorDialog(
                title = R.string.failed_to_save,
                message = it,
                onDismiss = onErrorDismissed,
            )
          }
        }
        item("subnetsToggle") {
          ListItemSwitch(
              R.string.use_tailscale_subnets,
              isOn = state.useSubnets,
              onToggle = toggleUseSubnets,
          )
        }
        item("subtitle") {
          ListItem(
              headlineContent = {
                Text(
                    stringResource(R.string.use_tailscale_subnets_subtitle),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
              }
          )
        }
        item("divider0") { Lists.SectionDivider() }
        item(key = "header") {
          Lists.MutedHeader(stringResource(R.string.advertised_routes))
          ListItem(
              headlineContent = {
                Text(
                    stringResource(R.string.run_as_subnet_router_header),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
              }
          )
        }

        itemsWithDividers(state.subnetRoutes, key = { it }) { route ->
          SubnetRouteRowView(
              route = route,
              onEdit = { startEditingRoute(route) },
              onDelete = { deleteRoute(route) },
              modifier = Modifier.animateItem(),
          )
        }

        item("addNewRoute") {
          Lists.ItemDivider()
          ListItem(
              headlineContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Icon(Icons.Outlined.Add, contentDescription = null)
                  Text(stringResource(R.string.add_new_route))
                }
              },
              modifier = Modifier.clickable { startEditingRoute("") },
          )
        }
      }
    }
  }
}

@Preview(showSystemUi = true)
@Composable
private fun SubnetRoutingPreview() {
  AppTheme {
    SubnetRoutingScreen(
        state =
            SubnetRoutingUIState(
                currentError = null,
                useSubnets = true,
                subnetRoutes = listOf("192.168.1.0/24", "192.168.2.0/24", "192.168.3.0/24"),
            ),
        openSubnetRouter = {},
        onErrorDismissed = {},
        toggleUseSubnets = {},
        startEditingRoute = {},
        deleteRoute = {},
        onBack = {},
        isTv = false,
    )
  }
}
