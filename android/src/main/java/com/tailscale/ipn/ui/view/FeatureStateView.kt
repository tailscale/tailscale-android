// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.ui.util.FeatureStateRepresentation

// FeatureStateView is a Composable that displays the contents of
// a FeatureStateRepresentation.
@Composable
fun FeatureStateView(state: FeatureStateRepresentation) {
  ListItem(
      leadingContent = {
        Icon(
            painter = painterResource(state.symbolDrawable),
            contentDescription = null,
            tint = state.tint,
            modifier = Modifier.size(64.dp))
      },
      headlineContent = {
        Text(stringResource(state.title), style = MaterialTheme.typography.titleMedium)
      },
      supportingContent = { Text(stringResource(state.caption)) })
}
