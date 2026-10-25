// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable

@Composable
fun TintedSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
  Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
}
