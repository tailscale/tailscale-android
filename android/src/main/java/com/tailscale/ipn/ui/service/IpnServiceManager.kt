// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.service

import android.content.Intent
import android.util.Log
import com.tailscale.ipn.App
import com.tailscale.ipn.IPNReceiver
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.localapi.LocalApiClient
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.CoroutineScope

// Actions that can be taken by the backend
interface IpnServiceActions {
    fun connect()
}

class IpnServiceManager(scope: CoroutineScope) : IpnServiceActions {
    private var notifier = Notifier()

    var apiClient = LocalApiClient(scope)
    var mdmSettings = MDMSettings()
    val model = IpnModel(notifier, apiClient, scope)

    override fun connect() {
        val context = App.getApplication().applicationContext
        val callback: (com.tailscale.ipn.ui.localapi.Result<Ipn.Prefs>) -> Unit = { result ->
            if (result.successful) {
                val prefs = result.success
                Log.d("IpnManager","Connect: preferences updated successfully: $prefs")
            } else if (result.failed) {
                val error = result.error
                Log.d("IpnManager","Connect: failed to update preferences: ${error?.message}")
            }
        }
        model.setWantRunning(true, callback)
    }
}
