// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class BugReportViewModel : ViewModel() {
  val bugReportID: StateFlow<String> = MutableStateFlow("")

  init {
    Client(viewModelScope).bugReportId { result ->
      result
          .onSuccess { bugReportID.set(it.trim()) }
          .onFailure { bugReportID.set("(Error fetching ID)") }
    }
  }
}
