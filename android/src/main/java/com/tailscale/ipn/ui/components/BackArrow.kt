// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun BackArrow(
    action: () -> Unit,
    focusRequester: FocusRequester,
    isTv: Boolean,
) {
  val isFocused = remember { mutableStateOf(false) }

  val boxModifier =
      if (isTv) {
        Modifier.focusRequester(focusRequester)
            .clip(CircleShape) // Ensure both the focus and click area are circular
            .background(
                if (isFocused.value) MaterialTheme.colorScheme.surface else Color.Transparent,
            )
            .onFocusChanged { focusState -> isFocused.value = focusState.isFocused }
            .focusable()
      } else {
        Modifier
      }

  val iconModifier =
      Modifier.clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = ripple(bounded = false, radius = 24.dp),
          onClick = action,
      )

  Box(modifier = boxModifier.padding(start = 8.dp, end = 8.dp)) {
    Icon(
        Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = "Go back to the previous screen",
        modifier = iconModifier,
    )
  }
}
