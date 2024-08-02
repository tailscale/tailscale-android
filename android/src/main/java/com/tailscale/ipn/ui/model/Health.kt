// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.tailscale.ipn.ui.theme.warning
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
      var ImpactsConnectivity: Boolean? = false,
      var DependsOn: List<String>? = null, // an array of WarnableCodes this depends on
  ) : Comparable<UnhealthyState> {
    fun hiddenByDependencies(currentWarnableCodes: Set<String>): Boolean {
      return this.DependsOn?.let {
        it.any { depWarnableCode -> currentWarnableCodes.contains(depWarnableCode) }
      } == true
    }

    override fun compareTo(other: UnhealthyState): Int {
      // Compare by severity first
      val severityComparison = Severity.compareTo(other.Severity)
      if (severityComparison != 0) {
        return severityComparison
      }

      // If severities are equal, compare by warnableCode
      return WarnableCode.compareTo(other.WarnableCode)
    }
  }

  @Serializable
  enum class Severity : Comparable<Severity> {
    low,
    medium,
    high;

    @Composable
    fun listItemColors(): ListItemColors {
      val default = ListItemDefaults.colors()
      return when (this) {
        Severity.low ->
            ListItemColors(
                containerColor = MaterialTheme.colorScheme.surface,
                headlineColor = MaterialTheme.colorScheme.secondary,
                leadingIconColor = MaterialTheme.colorScheme.secondary,
                overlineColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
                supportingTextColor = MaterialTheme.colorScheme.secondary,
                trailingIconColor = MaterialTheme.colorScheme.secondary,
                disabledHeadlineColor = default.disabledHeadlineColor,
                disabledLeadingIconColor = default.disabledLeadingIconColor,
                disabledTrailingIconColor = default.disabledTrailingIconColor)
        Severity.medium,
        Severity.high ->
            ListItemColors(
                containerColor = MaterialTheme.colorScheme.warning,
                headlineColor = MaterialTheme.colorScheme.onPrimary,
                leadingIconColor = MaterialTheme.colorScheme.onPrimary,
                overlineColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                supportingTextColor = MaterialTheme.colorScheme.onPrimary,
                trailingIconColor = MaterialTheme.colorScheme.onPrimary,
                disabledHeadlineColor = default.disabledHeadlineColor,
                disabledLeadingIconColor = default.disabledLeadingIconColor,
                disabledTrailingIconColor = default.disabledTrailingIconColor)
      }
    }
  }
}
