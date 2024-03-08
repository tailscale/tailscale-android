// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.service

import android.content.Intent
import com.tailscale.ipn.App
import com.tailscale.ipn.IPNReceiver
import com.tailscale.ipn.ui.localapi.LocalApiClient
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier

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
    var notifier = Notifier()
    var apiClient = LocalApiClient()
    val model: IpnModel = IpnModel(notifier, apiClient)

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
