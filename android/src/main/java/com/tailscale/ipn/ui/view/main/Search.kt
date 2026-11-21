// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R

@Composable
internal fun Search(
    onSearchBarClick: () -> Unit, // Callback for navigating to SearchView
) {
  // Prevent multiple taps
  var isNavigating by remember { mutableStateOf(false) }
  Box(
      modifier =
          Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(top = 8.dp),
  ) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .height(56.dp)
                .clip(MaterialTheme.shapes.extraLarge) // Rounded corners for search bar
                .background(MaterialTheme.colorScheme.background) // Search bar background
                .clickable(enabled = !isNavigating) { // Intercept taps
                  isNavigating = true
                  onSearchBarClick()
                }
                .padding(horizontal = 16.dp), // Internal padding
    ) {
      Row(
          verticalAlignment = Alignment.CenterVertically, // Ensure icon aligns with text
          modifier = Modifier.fillMaxSize(),
      ) {
        // Leading Icon
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = "Search",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 0.dp), // Optional start padding for alignment
        )
        Spacer(modifier = Modifier.width(4.dp))
        // Placeholder Text
        Text(
            text = stringResource(R.string.search_ellipsis),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f), // Ensure text takes up remaining space
        )
      }
    }
  }
}

@Preview
@Composable
private fun SearchPreview() {
  Search(onSearchBarClick = {})
}
