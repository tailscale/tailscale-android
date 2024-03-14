// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.localapi.LocalApiClient
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch


class BugReportViewModel(localAPI: LocalApiClient) : ViewModel() {
    val bugReportID: StateFlow<String> = MutableStateFlow("")

    init {
        viewModelScope.launch {
            localAPI.getBugReportId {
                when (it.successful) {
                    true -> bugReportID.set(it.success ?: "(Error fetching ID)")
                    false -> bugReportID.set("(Error fetching ID)")
                }
            }
        }
    }
}
