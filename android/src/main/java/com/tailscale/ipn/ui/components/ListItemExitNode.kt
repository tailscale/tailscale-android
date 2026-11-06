// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.ExitNode
import com.tailscale.ipn.ui.theme.disabledListItem
import com.tailscale.ipn.ui.theme.listItem
import kotlin.text.ifEmpty

@Composable
fun ListItemExitNode(
    node: ExitNode,
    forcedExitNodeId: String?,
    isRunningExitNode: Boolean,
    setExitNode: (ExitNode) -> Unit,
) {
  val online by node.online.collectAsState()
  val enabled = online && !isRunningExitNode && forcedExitNodeId == null

  Box {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled) { setExitNode(node) },
        colors =
            if (online && !isRunningExitNode) MaterialTheme.colorScheme.listItem
            else MaterialTheme.colorScheme.disabledListItem,
        headlineContent = {
          Text(node.city.ifEmpty { node.label }, style = MaterialTheme.typography.bodyMedium)
        },
        supportingContent = {
          if (!online)
              Text(
                  stringResource(R.string.offline),
                  style = MaterialTheme.typography.bodyMedium,
              )
        },
        trailingContent = {
          Row {
            if (node.selected) {
              Icon(Icons.Outlined.Check, null)
            }
          }
        },
    )
  }
}
