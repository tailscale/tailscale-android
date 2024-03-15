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

typealias PrefChangeCallback = (Result<Boolean>) -> Unit

// Abstracts the actions that can be taken by the UI so that the concept of an IPNManager
// itself is hidden from the viewModel implementations.
interface IpnViewActions {
    fun startVPN()
    fun stopVPN()
    fun login()
    fun logout()
    fun updatePrefs(prefs: Ipn.MaskedPrefs, callback: PrefChangeCallback)
}

class IpnViewManager(scope: CoroutineScope) : IpnViewActions {
    private var notifier = Notifier()

    var apiClient = LocalApiClient(scope)
    var mdmSettings = MDMSettings()
    val model = IpnModel(notifier, apiClient, scope)

    override fun startVPN() {
        val context = App.getApplication().applicationContext
        val intent = Intent(context, IPNReceiver::class.java)
        intent.action = IPNReceiver.INTENT_CONNECT_VPN
        context.sendBroadcast(intent)
    }

    override fun stopVPN() {
        val context = App.getApplication().applicationContext
        val intent = Intent(context, IPNReceiver::class.java)
        intent.action = IPNReceiver.INTENT_DISCONNECT_VPN
        context.sendBroadcast(intent)
    }
    
    override fun login() {
        apiClient.startLoginInteractive()
    }

    override fun logout() {
        apiClient.logout()
    }

    override fun updatePrefs(prefs: Ipn.MaskedPrefs, callback: PrefChangeCallback) {
        apiClient.editPrefs(prefs) { result ->
            result.success?.let {
                callback(Result.success(true))
            } ?: run {
                callback(Result.failure(Throwable(result.error)))
            }
        }
    }
}
