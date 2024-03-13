// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.service


import android.content.Intent
import com.tailscale.ipn.App
import com.tailscale.ipn.IPNReceiver
import com.tailscale.ipn.ui.localapi.LocalApiClient
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob


typealias PrefChangeCallback = (Result<Boolean>) -> Unit

// Abstracts the actions that can be taken by the UI so that the concept of an IPNManager
// itself is hidden from the viewModel implementations.
data class IpnActions(
    val startVPN: () -> Unit,
    val stopVPN: () -> Unit,
    val login: () -> Unit,
    val logout: () -> Unit,
    val openAdminConsole: () -> Unit,
    val updatePrefs: (Ipn.MaskedPrefs, PrefChangeCallback) -> Unit
)

class IpnManager {
    private var notifier = Notifier()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var apiClient = LocalApiClient(scope)
    val model = IpnModel(notifier, apiClient, scope)

    val actions = IpnActions(
            startVPN = { startVPN() },
            stopVPN = { stopVPN() },
            login = { apiClient.startLoginInteractive() },
            logout = { apiClient.logout() },
            openAdminConsole = { /* TODO */ },
            updatePrefs = { prefs, callback -> updatePrefs(prefs, callback) }
    )

    fun startVPN() {
        val context = App.getApplication().applicationContext
        val intent = Intent(context, IPNReceiver::class.java)
        intent.action = IPNReceiver.INTENT_CONNECT_VPN
        context.sendBroadcast(intent)
    }

    fun stopVPN() {
        val context = App.getApplication().applicationContext
        val intent = Intent(context, IPNReceiver::class.java)
        intent.action = IPNReceiver.INTENT_DISCONNECT_VPN
        context.sendBroadcast(intent)

    }

    fun updatePrefs(prefs: Ipn.MaskedPrefs, callback: PrefChangeCallback) {
        // (jonathan) TODO: Implement this in localAPI
        //apiClient.updatePrefs(prefs)
    }
}
