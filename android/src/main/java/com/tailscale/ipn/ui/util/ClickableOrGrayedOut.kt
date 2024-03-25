// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role

/**
 * Similar to Modifier.clickable, but if enabled == false, this adds a 75% alpha to make disabled
 * items appear grayed out.
 */
@Composable
fun Modifier.clickableOrGrayedOut(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit
) =
    if (enabled) {
      clickable(onClickLabel = onClickLabel, role = role, onClick = onClick)
    } else {
      alpha(0.75f)
    }
