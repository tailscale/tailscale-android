// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.theme.titledListItem

@Composable
fun ClipboardValueView(value: String, title: String? = null, subtitle: String? = null) {
  val localClipboardManager = LocalClipboardManager.current
  val modifier =
      Modifier.focusable()
          .clickable {
            localClipboardManager.setText(AnnotatedString(value))
          }

  ListItem(
      colors = MaterialTheme.colorScheme.titledListItem,
      modifier = modifier,
      overlineContent = title?.let { { Text(it, style = MaterialTheme.typography.titleMedium) } },
      headlineContent = { Text(text = value, style = MaterialTheme.typography.bodyMedium) },
      supportingContent =
          subtitle?.let {
            {
              Text(
                  it,
                  modifier = Modifier.padding(top = 8.dp),
                  style = MaterialTheme.typography.bodyMedium)
            }
          },
      trailingContent = {
        Icon(
            painterResource(R.drawable.clipboard),
            stringResource(R.string.copy_to_clipboard),
            modifier = Modifier.width(24.dp).height(24.dp))
      })
}
