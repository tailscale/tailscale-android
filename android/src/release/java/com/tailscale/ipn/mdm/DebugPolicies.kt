// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.mdm

import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Release build stub for developer-defined policy overrides. */
object DebugPolicies {
  /** Always false in release builds. */
  val isActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

  /** Always null in release builds: policy comes exclusively from `RestrictionsManager`. */
  fun overrideBundle(context: Context): Bundle? = null
}
