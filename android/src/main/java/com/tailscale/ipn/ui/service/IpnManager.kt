// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.service

import com.tailscale.ipn.ui.localapi.LocalApiClient
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class IpnManager {
    private var notifier = Notifier()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var apiClient = LocalApiClient(scope)
    private val model = IpnModel(notifier, apiClient, scope)

    // We share a single instance of the IPNManager across the entire application.
    companion object {
        @Volatile
        private var instance: IpnManager? = null

        fun getInstance() = instance ?: synchronized(this) {
            instance ?: IpnManager().also { instance = it }
        }
    }
}
