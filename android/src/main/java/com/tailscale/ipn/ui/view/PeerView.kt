// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.theme.off
import com.tailscale.ipn.ui.theme.on

@Composable
fun PeerView(
    peer: Tailcfg.Node,
    selfPeer: String? = null,
    stateVal: Ipn.State? = null,
    subtitle: () -> String = { peer.primaryIPv4Address ?: peer.primaryIPv6Address ?: "" },
    onClick: (Tailcfg.Node) -> Unit = {},
    trailingContent: @Composable () -> Unit = {}
) {
  val disabled = !(peer.Online ?: false)
  val textColor = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified

  ListItem(
      modifier = Modifier.clickable { onClick(peer) },
      headlineContent = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          // By definition, SelfPeer is online since we will not show the peer list
          // unless you're connected.
          val isSelfAndRunning = (peer.StableID == selfPeer && stateVal == Ipn.State.Running)
          val color: Color =
              if ((peer.Online == true) || isSelfAndRunning) {
                MaterialTheme.colorScheme.on
              } else {
                MaterialTheme.colorScheme.off
              }
          Box(
              modifier =
                  Modifier.size(8.dp)
                      .background(color = color, shape = RoundedCornerShape(percent = 50))) {}
          Spacer(modifier = Modifier.size(8.dp))
          Text(
              text = peer.displayName,
              style = MaterialTheme.typography.titleMedium,
              color = textColor)
        }
      },
      supportingContent = {
        Text(text = subtitle(), style = MaterialTheme.typography.bodyMedium, color = textColor)
      },
      trailingContent = trailingContent)
}
