// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.util

object FeatureFlags {

  // Map to hold the feature flags
  private val flags: MutableMap<String, Boolean> = mutableMapOf()

  fun initialize(defaults: Map<String, Boolean>) {
    flags.clear()
    flags.putAll(defaults)
  }

  fun enable(feature: String) {
    flags[feature] = true
  }

  fun disable(feature: String) {
    flags[feature] = false
  }

  fun isEnabled(feature: String): Boolean {
    return flags[feature] ?: false
  }
}
