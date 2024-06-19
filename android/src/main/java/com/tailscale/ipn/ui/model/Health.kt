// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import kotlinx.serialization.Serializable

class Health {
  @Serializable
  data class State(
      // WarnableCode -> UnhealthyState or null
      var Warnings: Map<String, UnhealthyState?>? = null,
  )

  @Serializable
  data class UnhealthyState(
      var WarnableCode: String,
      var Severity: Severity,
      var Title: String,
      var Text: String,
      var BrokenSince: String? = null,
      var Args: Map<String, String>? = null,
      var DependsOn: List<String>? = null, // an array of WarnableCodes this depends on
  ) {
    fun hiddenByDependencies(currentWarnableCodes: Set<String>): Boolean {
      return this.DependsOn?.let {
        it.any { depWarnableCode -> currentWarnableCodes.contains(depWarnableCode) }
      } == true
    }
  }

  @Serializable
  enum class Severity {
    high,
    medium,
    low
  }
}
