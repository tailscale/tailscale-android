// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.PeerSet.Companion.FAVORITES_ID

@Composable
internal fun NodesSectionHeader(
    peerSet: PeerSet,
    focusable: Boolean,
) {
  Lists.LargeTitle(
      title = peerSet.sectionTitle,
      modifier =
          Modifier.background(color = MaterialTheme.colorScheme.surface).padding(top = 16.dp),
      bottomPadding = 8.dp,
      focusable = focusable,
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.SemiBold,
      leadingIcon =
          if (peerSet.isFavorite) {
            {
              Icon(
                  painter = painterResource(R.drawable.pin_24),
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else null,
  )
}

@Preview(showBackground = true)
@Composable
private fun NodesSectionHeaderPreview() {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    NodesSectionHeader(
        peerSet = PeerSet(FAVORITES_ID, null, nodes = emptyList()),
        focusable = false,
    )
    NodesSectionHeader(peerSet = PeerSet(1, "Thing", nodes = emptyList()), focusable = false)
  }
}
