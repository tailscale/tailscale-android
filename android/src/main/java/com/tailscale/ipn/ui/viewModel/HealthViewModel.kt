// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.model.Health
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HealthViewModel : ViewModel() {
  val warnings: StateFlow<List<Health.UnhealthyState>> = MutableStateFlow(listOf())

  init {
    viewModelScope.launch {
      App.get().healthNotifier?.currentWarnings?.collect { set -> warnings.set(set.sorted()) }
    }
  }
}
