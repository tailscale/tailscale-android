// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.dataSource.HealthDataSource
import com.tailscale.ipn.ui.model.Health
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HealthViewModelFactory(
    private val dataSource: HealthDataSource,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(HealthViewModel::class.java)) {
      return HealthViewModel(dataSource) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}

class HealthViewModel(
    private val dataSource: HealthDataSource,
) : ViewModel() {
  private val _warnings = MutableStateFlow<List<Health.UnhealthyState>>(emptyList())
  val warnings: StateFlow<List<Health.UnhealthyState>> = _warnings

  init {
    viewModelScope.launch {
      dataSource.currentWarnings.collect { warnings -> _warnings.value = warnings.sorted() }
    }
  }
}
