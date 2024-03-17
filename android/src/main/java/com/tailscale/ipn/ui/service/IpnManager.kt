// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.service


import android.content.Intent
import android.util.Log
import com.tailscale.ipn.App
import com.tailscale.ipn.IPNReceiver
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.CoroutineScope

typealias PrefChangeCallback = (Result<Boolean>) -> Unit

// Abstracts the actions that can be taken by the UI so that the concept of an IPNManager
// itself is hidden from the viewModel implementations.
interface IpnActions {
    fun startVPN()
    fun stopVPN()
    fun login()
    fun logout()
    fun updatePrefs(prefs: Ipn.MaskedPrefs, callback: PrefChangeCallback)
}

class IpnManager(private val scope: CoroutineScope) : IpnActions {
    companion object {
        private const val TAG = "IpnManager"
    }

    private var notifier = Notifier(scope)
    var mdmSettings = MDMSettings()
    val model = IpnModel(notifier, scope)

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
        Client(scope).startLoginInteractive { result ->
            result.onSuccess {
                Log.d(TAG, "Login started: $it")
            }.onFailure {
                Log.e(TAG, "Error starting login: ${it.message}")
            }
        }
    }

    override fun logout() {
        Client(scope).logout { result ->
            result.onSuccess {
                Log.d(TAG, "Logout started: $it")
            }.onFailure {
                Log.e(TAG, "Error starting logout: ${it.message}")
            }
        }
    }

    override fun updatePrefs(prefs: Ipn.MaskedPrefs, callback: PrefChangeCallback) {
        Client(scope).editPrefs(prefs) { result ->
            result.onSuccess {
                callback(Result.success(true))
            }.onFailure {
                callback(Result.failure(it))
            }
        }
    }
}
