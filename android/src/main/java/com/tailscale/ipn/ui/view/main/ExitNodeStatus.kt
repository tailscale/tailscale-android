// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.NodeState
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.theme.customErrorContainer
import com.tailscale.ipn.ui.theme.errorButton
import com.tailscale.ipn.ui.theme.errorListItem
import com.tailscale.ipn.ui.theme.exitNodeToggleButton
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.theme.primaryListItem
import com.tailscale.ipn.ui.theme.secondaryButton
import com.tailscale.ipn.ui.theme.warningButton
import com.tailscale.ipn.ui.theme.warningListItem
import com.tailscale.ipn.ui.view.MainViewNavigation
import com.tailscale.ipn.ui.viewModel.MainViewModel

@Composable
internal fun ExitNodeStatus(
    viewModel: MainViewModel,
    navigation: MainViewNavigation,
    prefs: Ipn.Prefs,
) {
  val nodeState by viewModel.nodeState.collectAsState()
  val netmap by viewModel.netmap.collectAsState()
  val managedByOrganization by viewModel.managedByOrganization.collectAsState()

  ExitNodeStatus(
      state =
          ExitNodeStatusUIState(
              nodeState = nodeState,
              prefs = prefs,
              netmap = netmap,
              managedByOrganization = managedByOrganization.value,
          ),
      toggleExitNode = viewModel::toggleExitNode,
      setRunningExitNode = viewModel::setRunningExitNode,
      navAction = navigation.onNavigateToExitNodes,
  )
}

private data class ExitNodeStatusUIState(
    val nodeState: NodeState,
    val prefs: Ipn.Prefs,
    val netmap: Netmap.NetworkMap?,
    val managedByOrganization: String?,
)

@Composable
private fun ExitNodeStatus(
    state: ExitNodeStatusUIState,
    setRunningExitNode: (Boolean) -> Unit,
    toggleExitNode: () -> Unit,
    navAction: () -> Unit,
) {
  // The activeExitNode is the source of truth.  The selectedExitNode is only relevant if we
  // don't have an active node.
  val chosenExitNodeId = state.prefs.activeExitNodeID ?: state.prefs.selectedExitNodeID
  val exitNodePeer = chosenExitNodeId?.let { id -> state.netmap?.Peers?.find { it.StableID == id } }
  val name = exitNodePeer?.exitNodeName

  Box(
      modifier =
          Modifier.fillMaxWidth().background(color = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    if (state.nodeState == NodeState.OFFLINE_MDM) {
      Box(
          modifier =
              Modifier.padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 16.dp)
                  .clip(shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 10.dp))
                  .background(MaterialTheme.colorScheme.customErrorContainer)
                  .fillMaxWidth()
                  .align(Alignment.TopCenter),
      ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 36.dp, bottom = 16.dp),
        ) {
          Text(
              text =
                  state.managedByOrganization?.let {
                    stringResource(R.string.exit_node_offline_mdm_orgname, it)
                  } ?: stringResource(R.string.exit_node_offline_mdm),
              style = MaterialTheme.typography.bodyMedium,
              color = Color.White,
          )
        }
      }
    }
    Box(
        modifier =
            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
                .clip(shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 10.dp))
                .fillMaxWidth(),
    ) {
      ListItem(
          modifier = Modifier.clickable { navAction() },
          colors =
              when (state.nodeState) {
                NodeState.ACTIVE_AND_RUNNING -> MaterialTheme.colorScheme.primaryListItem
                NodeState.ACTIVE_NOT_RUNNING -> MaterialTheme.colorScheme.listItem
                NodeState.RUNNING_AS_EXIT_NODE -> MaterialTheme.colorScheme.warningListItem
                NodeState.OFFLINE_ENABLED -> MaterialTheme.colorScheme.errorListItem
                NodeState.OFFLINE_DISABLED -> MaterialTheme.colorScheme.errorListItem
                NodeState.OFFLINE_MDM -> MaterialTheme.colorScheme.errorListItem
                else -> ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
              },
          overlineContent = {
            Text(
                text =
                    if (
                        state.nodeState == NodeState.OFFLINE_ENABLED ||
                            state.nodeState == NodeState.OFFLINE_DISABLED ||
                            state.nodeState == NodeState.OFFLINE_MDM
                    )
                        stringResource(R.string.exit_node_offline)
                    else stringResource(R.string.exit_node),
                style = MaterialTheme.typography.bodySmall,
            )
          },
          headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                  text =
                      when (state.nodeState) {
                        NodeState.NONE -> stringResource(id = R.string.none)
                        NodeState.RUNNING_AS_EXIT_NODE ->
                            stringResource(id = R.string.running_exit_node)
                        else -> name ?: ""
                      },
                  style = MaterialTheme.typography.bodyMedium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
              )
              Icon(
                  imageVector = Icons.Outlined.ArrowDropDown,
                  contentDescription = null,
                  tint =
                      if (state.nodeState == NodeState.NONE)
                          MaterialTheme.colorScheme.onSurfaceVariant
                      else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
              )
            }
          },
          trailingContent = {
            if (state.nodeState != NodeState.NONE) {
              Button(
                  colors =
                      when (state.nodeState) {
                        NodeState.OFFLINE_ENABLED -> MaterialTheme.colorScheme.errorButton
                        NodeState.OFFLINE_DISABLED -> MaterialTheme.colorScheme.errorButton
                        NodeState.OFFLINE_MDM -> MaterialTheme.colorScheme.errorButton
                        NodeState.RUNNING_AS_EXIT_NODE -> MaterialTheme.colorScheme.warningButton
                        NodeState.ACTIVE_NOT_RUNNING ->
                            MaterialTheme.colorScheme.exitNodeToggleButton
                        else -> MaterialTheme.colorScheme.secondaryButton
                      },
                  onClick = {
                    if (state.nodeState == NodeState.RUNNING_AS_EXIT_NODE) setRunningExitNode(false)
                    else toggleExitNode()
                  },
              ) {
                Text(
                    when (state.nodeState) {
                      NodeState.OFFLINE_DISABLED -> stringResource(id = R.string.enable)
                      NodeState.ACTIVE_NOT_RUNNING -> stringResource(id = R.string.enable)
                      NodeState.RUNNING_AS_EXIT_NODE -> stringResource(id = R.string.stop)
                      else -> stringResource(id = R.string.disable)
                    },
                )
              }
            }
          },
      )
    }
  }
}

@Preview
@Composable
private fun ExitNodeStatusPreview() {
  val node =
      Tailcfg.Node(
          ID = 1,
          StableID = "1",
          Name = "my-exit-node",
      )

  val netmap =
      Netmap.NetworkMap(
          SelfNode = node,
          Peers = listOf(node),
          Domain = "tailscale.com",
          TKAEnabled = false,
          UserProfiles = emptyMap(),
      )

  val state =
      ExitNodeStatusUIState(
          nodeState = NodeState.ACTIVE_AND_RUNNING,
          netmap = netmap,
          prefs = Ipn.Prefs(ExitNodeID = "1"),
          managedByOrganization = null,
      )

  AppTheme {
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
      ExitNodeStatus(
          state = state,
          toggleExitNode = {},
          setRunningExitNode = {},
          navAction = {},
      )
    }
  }
}
