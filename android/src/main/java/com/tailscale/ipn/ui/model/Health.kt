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
  companion object {
    // Warnable codes of the health subsystem in the Go backend
    // (tailscale.com/tsconst.HealthWarnable*). Keep in sync with the backend.
    const val WARNABLE_LOGIN_STATE = "login-state"
    const val WARNABLE_NETWORK_STATUS = "network-status"
    const val WARNABLE_WANT_RUNNING_FALSE = "wantrunning-false"
    const val WARNABLE_IS_USING_UNSTABLE_VERSION = "is-using-unstable-version"
    const val WARNABLE_WARMING_UP = "warming-up"

    // Argument names used by warnables in the Go backend (tailscale.com/health.Arg*).
    const val ARG_ERROR = "error"
  }

  @Serializable
  data class State(
      // WarnableCode -> UnhealthyState or null
      var Warnings: Map<String, UnhealthyState?>? = null,
  ) {
    /**
     * The last login error reported by the backend via the login-state warnable, or null if the
     * backend reported no login error (yet). See tailscale.com/health LoginStateWarnable.
     */
    val loginStateError: String?
      get() = Warnings?.get(WARNABLE_LOGIN_STATE)?.Args?.get(ARG_ERROR)?.takeIf { it.isNotEmpty() }
  }

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

    /** Hydrated value copied to the clipboard for debugging. */
    val clipboardText: String
      get() = "$WarnableCode: $Title" + if (Text.isNotEmpty()) "\n$Text" else ""

    override fun compareTo(other: UnhealthyState): Int {
      // Compare by severity first
      val severityComparison = other.Severity.compareTo(Severity)
      if (severityComparison != 0) {
        return severityComparison
      }

      // If severities are equal, connectivity impacting issues first
      val connectivityComparison =
          (other.ImpactsConnectivity ?: false).compareTo(ImpactsConnectivity ?: false)
      if (connectivityComparison != 0) {
        return connectivityComparison
      }

      // Otherwise, alphabetical by WarnableCode
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
                disabledTrailingIconColor = default.disabledTrailingIconColor,
            )
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
                disabledTrailingIconColor = default.disabledTrailingIconColor,
            )
      }
    }
  }
}
