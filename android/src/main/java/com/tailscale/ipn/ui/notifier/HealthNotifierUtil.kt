// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import com.tailscale.ipn.ui.model.Health
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Injects a fake Health.State for testing purposes.
 * This bypasses the normal IPN bus notification flow.
 */
fun Notifier.injectFakeHealthState(
    includeHighSeverity: Boolean = true,
    includeConnectivityImpact: Boolean = false,
    customWarnings: List<Health.UnhealthyState> = emptyList()
) { 
  val warnings = mutableMapOf<String, Health.UnhealthyState?>()
  
  if (includeHighSeverity) {
    warnings["test-high-severity"] = Health.UnhealthyState(
        WarnableCode = "test-high-severity",
        Severity = Health.Severity.high,
        Title = "Test High Severity Warning",
        Text = "This is a test warning with high severity",
        ImpactsConnectivity = includeConnectivityImpact,
        DependsOn = null
    )
  }
  
  warnings["test-low-severity"] = Health.UnhealthyState(
      WarnableCode = "test-low-severity",
      Severity = Health.Severity.low,
      Title = "Test Low Severity Warning",
      Text = "This is a test warning with low severity",
      ImpactsConnectivity = false,
      DependsOn = null
  )
  
  customWarnings.forEach { warning ->
    warnings[warning.WarnableCode] = warning
  }
  
  (health as MutableStateFlow).set(Health.State(Warnings = warnings))
}
