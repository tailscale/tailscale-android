// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.compose.ui.Modifier

/// Applies different modifiers to the receiver based on a condition.
inline fun Modifier.conditional(
    condition: Boolean,
    ifTrue: Modifier.() -> Modifier,
    ifFalse: Modifier.() -> Modifier = { this },
): Modifier =
    if (condition) {
      then(ifTrue(Modifier))
    } else {
      then(ifFalse(Modifier))
    }
