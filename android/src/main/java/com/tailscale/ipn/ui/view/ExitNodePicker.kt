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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.ShowHide
import com.tailscale.ipn.ui.components.Header
import com.tailscale.ipn.ui.components.ListItemExitNode
import com.tailscale.ipn.ui.components.ListItemSwitch
import com.tailscale.ipn.ui.dataSource.ExitNodePickerDataSource
import com.tailscale.ipn.ui.model.ExitNode
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.theme.disabledListItem
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.util.AndroidTVUtil
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.ExitNodePickerNav
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModel
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModelFactory
import com.tailscale.ipn.ui.viewModel.selected
import kotlin.String
import kotlin.collections.component1
import kotlin.collections.component2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun ExitNodePickerView(
    exitNodePickerNav: ExitNodePickerNav,
    dataSource: ExitNodePickerDataSource,
) {
  val factory = remember(dataSource) { ExitNodePickerViewModelFactory(dataSource) }
  val exitNodePickerViewModel: ExitNodePickerViewModel = viewModel(factory = factory)

  val tailnetExitNodes by exitNodePickerViewModel.tailnetExitNodes.collectAsStateWithLifecycle()
  val mullvadExitNodesByCountryCode by
      exitNodePickerViewModel.mullvadExitNodesByCountryCode.collectAsStateWithLifecycle()
  val mullvadExitNodeCount by
      exitNodePickerViewModel.mullvadExitNodeCount.collectAsStateWithLifecycle()
  val anyActive by exitNodePickerViewModel.anyActive.collectAsStateWithLifecycle()
  val shouldShowMullvadInfo by
      exitNodePickerViewModel.shouldShowMullvadInfo.collectAsStateWithLifecycle()
  val managedByOrgSetting by
      MDMSettings.managedByOrganizationName.flow.collectAsStateWithLifecycle()
  val isRunningExitNode by exitNodePickerViewModel.isRunningExitNode.collectAsStateWithLifecycle()
  val allowLANAccess by dataSource.prefs.collectAsStateWithLifecycle()
  val showRunAsExitNode by MDMSettings.runExitNode.flow.collectAsStateWithLifecycle()
  val allowLanAccessMDMDisposition by
      MDMSettings.exitNodeAllowLANAccess.flow.collectAsStateWithLifecycle()
  val forcedExitNodeId by MDMSettings.exitNodeID.flow.collectAsStateWithLifecycle()
  val actionScope = rememberCoroutineScope()

  val duplicateExitNodeIDs = tailnetExitNodes.groupBy { it.id }.filterValues { it.size > 1 }

  check(duplicateExitNodeIDs.isEmpty()) {
    "Duplicate exit node IDs in ExitNodePicker: " +
        duplicateExitNodeIDs.entries.joinToString("; ") { (id, nodes) ->
          "$id(count=${nodes.size})"
        }
  }

  LoadingIndicator.Wrap {
    ExitNodePickerScreen(
        state =
            ExitNodePickerUIState(
                forcedExitNodeId = forcedExitNodeId.value,
                managedByOrganization = managedByOrgSetting.value,
                anyActive = anyActive,
                isRunningExitNode = isRunningExitNode,
                showRunAsExitNode = showRunAsExitNode.value == ShowHide.Show,
                mullvadExitNodeCount = mullvadExitNodeCount,
                mullvadExitNodesByCountryCode = mullvadExitNodesByCountryCode,
                shouldShowMullvadInfo = shouldShowMullvadInfo,
                tailnetExitNodes = tailnetExitNodes,
                showLanAccess = !allowLanAccessMDMDisposition.value.hiddenFromUser,
                allowLanAccess = allowLANAccess?.ExitNodeAllowLANAccess == true,
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
        toggleAllowLANAccess = { _ ->
          actionScope.launch {
            LoadingIndicator.start()
            try {
              exitNodePickerViewModel.toggleAllowLANAccess()
            } finally {
              LoadingIndicator.stop()
            }
          }
        },
        nav = exitNodePickerNav,
        isTv = AndroidTVUtil.isAndroidTV(),
    )
  }
}

@Composable
private fun MullvadItem(
    onNavigateToMullvad: () -> Unit,
    count: Int,
    selected: Boolean,
) {
  Box {
    ListItem(
        modifier = Modifier.clickable { onNavigateToMullvad() },
        headlineContent = {
          Text(
              stringResource(R.string.mullvad_exit_nodes),
              style = MaterialTheme.typography.bodyMedium,
          )
        },
        supportingContent = {
          Text(
              "$count ${stringResource(R.string.countries)}",
              style = MaterialTheme.typography.bodyMedium,
          )
        },
        trailingContent = {
          if (selected) {
            Icon(Icons.Outlined.Check, null)
          }
        },
    )
  }
}

@Composable
private fun MullvadInfoItem(onNavigateToMullvadInfo: () -> Unit) {
  Box {
    ListItem(
        modifier = Modifier.clickable { onNavigateToMullvadInfo() },
        headlineContent = {
          Text(
              stringResource(R.string.mullvad_exit_nodes),
              style = MaterialTheme.typography.bodyMedium,
          )
        },
        supportingContent = {
          Text(
              stringResource(R.string.enable_in_the_admin_console),
              style = MaterialTheme.typography.bodyMedium,
          )
        },
    )
  }
}

@Composable
private fun RunAsExitNodeItem(
    onNavigateToRunAsExitNode: () -> Unit,
    isRunningExitNode: Boolean,
    anyActive: Boolean,
) {
  Box {
    ListItem(
        modifier =
            Modifier.clickable(
                enabled = !anyActive,
                onClick = onNavigateToRunAsExitNode,
            ),
        colors =
            if (!anyActive) MaterialTheme.colorScheme.listItem
            else MaterialTheme.colorScheme.disabledListItem,
        headlineContent = {
          Text(
              stringResource(id = R.string.run_as_exit_node),
              style = MaterialTheme.typography.bodyMedium,
          )
        },
        supportingContent = {
          if (isRunningExitNode) {
            Text(stringResource(R.string.enabled))
          } else {
            Text(stringResource(R.string.disabled))
          }
        },
    )
  }
}

private data class ExitNodePickerUIState(
    val forcedExitNodeId: String?,
    val managedByOrganization: String?,
    val anyActive: Boolean,
    val isRunningExitNode: Boolean,
    val showRunAsExitNode: Boolean,
    val mullvadExitNodeCount: Int,
    val mullvadExitNodesByCountryCode: Map<String, List<ExitNode>>,
    val shouldShowMullvadInfo: Boolean,
    val tailnetExitNodes: List<ExitNode>,
    val showLanAccess: Boolean,
    val allowLanAccess: Boolean,
)

@Composable
private fun ExitNodePickerScreen(
    state: ExitNodePickerUIState,
    setExitNode: (ExitNode) -> Unit,
    toggleAllowLANAccess: (Boolean) -> Unit,
    nav: ExitNodePickerNav,
    isTv: Boolean,
) {
  Scaffold(
      topBar = {
        Header(
            R.string.choose_exit_node,
            onBack = nav.onNavigateBackHome,
            isTv = isTv,
        )
      }
  ) { innerPadding ->
    LazyColumn(modifier = Modifier.padding(innerPadding)) {
      item(key = "header") {
        if (state.forcedExitNodeId != null) {
          val text =
              state.managedByOrganization?.let {
                stringResource(R.string.exit_node_mdm_orgname, it)
              } ?: stringResource(R.string.exit_node_mdm)
          Text(
              text = text,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
          )
        } else {
          ListItemExitNode(
              node =
                  ExitNode(
                      label = stringResource(R.string.none),
                      online = MutableStateFlow(true),
                      selected = !state.anyActive,
                  ),
              forcedExitNodeId = state.forcedExitNodeId,
              isRunningExitNode = state.isRunningExitNode,
              setExitNode = setExitNode,
          )
        }
        if (state.showRunAsExitNode) {
          Lists.ItemDivider()
          RunAsExitNodeItem(
              onNavigateToRunAsExitNode = nav.onNavigateToRunAsExitNode,
              isRunningExitNode = state.isRunningExitNode,
              anyActive = state.anyActive,
          )
        }
      }

      item(key = "divider1") { Lists.SectionDivider() }

      itemsWithDividers(state.tailnetExitNodes, key = { it.id!! }) { node ->
        ListItemExitNode(
            node = node,
            forcedExitNodeId = state.forcedExitNodeId,
            isRunningExitNode = state.isRunningExitNode,
            setExitNode = setExitNode,
        )
      }

      if (state.mullvadExitNodeCount > 0) {
        item(key = "mullvad") {
          Lists.SectionDivider()
          MullvadItem(
              onNavigateToMullvad = nav.onNavigateToMullvad,
              count = state.mullvadExitNodesByCountryCode.size,
              selected = state.mullvadExitNodesByCountryCode.selected,
          )
        }
      } else if (state.shouldShowMullvadInfo) {
        item(key = "mullvad_info") {
          Lists.SectionDivider()
          MullvadInfoItem(onNavigateToMullvadInfo = nav.onNavigateToMullvadInfo)
        }
      }

      if (state.showLanAccess) {
        item(key = "allowLANAccess") {
          Lists.SectionDivider()

          ListItemSwitch(
              R.string.allow_lan_access,
              isOn = state.allowLanAccess,
              onToggle = toggleAllowLANAccess,
          )
        }
      }
    }
  }
}

@Preview(showSystemUi = true)
@Composable
private fun ExitNodePickerPreview() {
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
    ExitNodePickerScreen(
        state =
            ExitNodePickerUIState(
                forcedExitNodeId = "1",
                managedByOrganization = "Tails and Scales Inc.",
                anyActive = true,
                isRunningExitNode = false,
                showRunAsExitNode = true,
                mullvadExitNodeCount = 10,
                mullvadExitNodesByCountryCode = mapOf(Pair("", listOf(node1))),
                shouldShowMullvadInfo = true,
                tailnetExitNodes = listOf(node1, node2),
                showLanAccess = true,
                allowLanAccess = true,
            ),
        setExitNode = {},
        toggleAllowLANAccess = {},
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
