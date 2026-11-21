// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.theme.warningListItem

@Composable
internal fun ExpiryNotification(
    netmap: Netmap.NetworkMap,
    action: () -> Unit,
) {
  Box(modifier = Modifier.background(color = MaterialTheme.colorScheme.surfaceContainer)) {
    Box(
        modifier =
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                .clip(shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 10.dp))
                .fillMaxWidth(),
    ) {
      ListItem(
          modifier = Modifier.clickable { action() },
          colors = MaterialTheme.colorScheme.warningListItem,
          headlineContent = {
            Text(
                netmap.SelfNode.expiryLabel(),
                style = MaterialTheme.typography.titleMedium,
            )
          },
          supportingContent = {
            Text(
                stringResource(id = R.string.keyExpiryExplainer),
                style = MaterialTheme.typography.bodyMedium,
            )
          },
      )
    }
  }
}

@Preview
@Composable
private fun ExpiryNotificationPreview() {
  ExpiryNotification(
      netmap =
          Netmap.NetworkMap(
              SelfNode = Tailcfg.Node(),
              Peers = emptyList(),
              Domain = "tailscale.com",
              UserProfiles = emptyMap(),
              TKAEnabled = false,
          ),
      action = {},
  )
}
