// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

// The window width at which there is room to render two panes side by side. This is the
// medium window size class breakpoint: unfolded foldables, tablets, and large landscape
// windows are at or above it, while phones and folded foldables are below it.
private const val TWO_PANE_MIN_WIDTH_DP = 600

/**
 * Returns true if the window is wide enough to render a list and the detail of the selected item
 * side by side. Recomposes when the window changes size, e.g. when a foldable is unfolded or the
 * app is resized in split screen.
 *
 * Android TV always uses a single pane: its UI is letterboxed by [Modifier.universalFit] and is
 * driven by a remote rather than touch.
 */
@Composable
fun isTwoPaneWindow(): Boolean {
  if (AndroidTVUtil.isAndroidTV()) return false
  return LocalConfiguration.current.screenWidthDp >= TWO_PANE_MIN_WIDTH_DP
}
