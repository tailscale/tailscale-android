// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color

// FeatureStateRepresentation represents the status of a feature
// in the UI, by providing a symbol, a title, and a caption.
// It is typically implemented as an enumeration.
interface FeatureStateRepresentation {
  @get:DrawableRes val symbolDrawable: Int
  val tint: Color
  @get:StringRes val title: Int
  @get:StringRes val caption: Int
}
