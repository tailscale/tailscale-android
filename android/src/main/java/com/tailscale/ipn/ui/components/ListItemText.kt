// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.tailscale.ipn.ui.theme.listItem

@Composable
fun ListItemText(
    titleRes: Int = 0,
    title: String? = null,
    subtitle: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
  var modifier: Modifier = Modifier
  if (enabled) {
    onClick?.let { modifier = modifier.clickable(onClick = it) }
  }
  ListItem(
      modifier = modifier,
      colors = MaterialTheme.colorScheme.listItem,
      headlineContent = {
        Text(
            title ?: stringResource(titleRes),
            style = MaterialTheme.typography.bodyMedium,
            color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
      },
      supportingContent =
          subtitle?.let {
            {
              Text(
                  it,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
  )
}
