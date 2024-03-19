// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tailscale.ipn.R

// Convenience wrapper for passing formatted strings to Composables
class ComposableStringFormatter(
    @StringRes val stringRes: Int = R.string.template,
    private vararg val params: Any
) {

  // Convenience constructor for passing a non-formatted string directly
  constructor(string: String) : this(stringRes = R.string.template, string)

  // Returns the fully formatted string
  @Composable fun getString(): String = stringResource(id = stringRes, *params)
}
