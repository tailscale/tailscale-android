// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.localapi.LocalApiClient
import com.tailscale.ipn.ui.model.BugReportID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch


class BugReportViewModel(localAPI: LocalApiClient) : ViewModel() {
    private var _bugReportID: MutableStateFlow<BugReportID> = MutableStateFlow("")
    var bugReportID: StateFlow<String> = _bugReportID

    init {
        viewModelScope.launch {
            localAPI.getBugReportId {
                when (it.successful) {
                    true -> _bugReportID.value = it.success ?: "(Error fetching ID)"
                    false -> _bugReportID.value = "(Error fetching ID)"
                }
            }
        }
    }
}
