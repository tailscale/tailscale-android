// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import com.tailscale.ipn.ui.theme.ts_color_light_blue

@Composable
fun TintedSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
  Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      enabled = enabled,
      colors =
          SwitchDefaults.colors(
              checkedBorderColor = ts_color_light_blue,
              checkedThumbColor = ts_color_light_blue,
              checkedTrackColor = ts_color_light_blue.copy(alpha = 0.3f),
              uncheckedTrackColor = MaterialTheme.colorScheme.secondaryContainer))
}
