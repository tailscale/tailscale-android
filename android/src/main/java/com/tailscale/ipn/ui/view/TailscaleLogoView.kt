// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// TODO(angott):
// - Implement game-of-life animation for progress indicator.
// - Remove hardcoded dots, use a for-each and make it dynamically
//   use the space available instead of unit = 10.dp

@Composable
fun TailscaleLogoView(modifier: Modifier) {
  val primaryColor: Color = MaterialTheme.colorScheme.primary
  val secondaryColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
  BoxWithConstraints(modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(this@BoxWithConstraints.maxWidth.div(8))) {
      Row(horizontalArrangement = Arrangement.spacedBy(this@BoxWithConstraints.maxWidth.div(8))) {
        Canvas(
            modifier = Modifier.size(this@BoxWithConstraints.maxWidth.div(4)),
            onDraw = { drawCircle(color = secondaryColor) })
        Canvas(
            modifier = Modifier.size(this@BoxWithConstraints.maxWidth.div(4)),
            onDraw = { drawCircle(color = secondaryColor) })
        Canvas(
            modifier = Modifier.size(this@BoxWithConstraints.maxWidth.div(4)),
            onDraw = { drawCircle(color = secondaryColor) })
      }
      Row(horizontalArrangement = Arrangement.spacedBy(this@BoxWithConstraints.maxWidth.div(8))) {
        Canvas(
            modifier = Modifier.size(this@BoxWithConstraints.maxWidth.div(4)),
            onDraw = { drawCircle(color = primaryColor) })
        Canvas(
            modifier = Modifier.size(this@BoxWithConstraints.maxWidth.div(4)),
            onDraw = { drawCircle(color = primaryColor) })
        Canvas(
            modifier = Modifier.size(this@BoxWithConstraints.maxWidth.div(4)),
            onDraw = { drawCircle(color = primaryColor) })
      }
      Row(horizontalArrangement = Arrangement.spacedBy(this@BoxWithConstraints.maxWidth.div(8))) {
        Canvas(
            modifier = Modifier.size(this@BoxWithConstraints.maxWidth.div(4)),
            onDraw = { drawCircle(color = secondaryColor) })
        Canvas(
            modifier = Modifier.size(this@BoxWithConstraints.maxWidth.div(4)),
            onDraw = { drawCircle(color = primaryColor) })
        Canvas(
            modifier = Modifier.size(this@BoxWithConstraints.maxWidth.div(4)),
            onDraw = { drawCircle(color = secondaryColor) })
      }
    }
  }
}
