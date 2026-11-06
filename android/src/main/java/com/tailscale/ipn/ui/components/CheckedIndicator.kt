// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import com.tailscale.ipn.ui.theme.ts_color_light_blue

@Composable
fun CheckedIndicator() {
  Icon(Icons.Default.CheckCircle, null, tint = ts_color_light_blue)
}
