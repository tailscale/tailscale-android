// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.dataSource

import com.tailscale.ipn.App
import com.tailscale.ipn.ui.model.Health
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface HealthDataSource {
  val currentWarnings: StateFlow<Set<Health.UnhealthyState>>
}

class AndroidHealthDataSource : HealthDataSource {
  override val currentWarnings: StateFlow<Set<Health.UnhealthyState>> =
      App.get().healthNotifier?.currentWarnings ?: MutableStateFlow(emptySet())
}
