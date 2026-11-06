// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.ui.theme.ts_color_light_blue

@Composable
fun ActivityIndicator(progress: Double, size: Int = 32) {
  // LinearProgressIndicator defaults to a 4.dp height in Material3.
  val height = 4.dp

  LinearProgressIndicator(
      progress = { progress.toFloat() },
      modifier = Modifier.width(size.dp),
      color = ts_color_light_blue,
      trackColor = MaterialTheme.colorScheme.secondary,
      gapSize = -height,
      drawStopIndicator = {},
  )
}
