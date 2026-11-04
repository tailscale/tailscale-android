// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem as Builtin
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DropdownMenuItem(text: String, onClick: () -> Unit) {
  Builtin(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 0.dp),
      onClick = onClick,
      text = { Text(text = text) },
  )
}
