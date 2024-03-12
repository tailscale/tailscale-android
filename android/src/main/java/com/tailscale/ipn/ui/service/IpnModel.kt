// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.service

import android.util.Log
import com.tailscale.ipn.ui.localapi.LocalApiClient
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class IpnModel(
    notifier: Notifier,
    private val apiClient: LocalApiClient,
    scope: CoroutineScope
) {
    private var notifierSessions: MutableList<String> = mutableListOf()


    private val _state: MutableStateFlow<Ipn.State> = MutableStateFlow(Ipn.State.NoState)
    private val _netmap: MutableStateFlow<Netmap.NetworkMap?> = MutableStateFlow(null)
    private val _prefs: MutableStateFlow<Ipn.Prefs?> = MutableStateFlow(null)
    private val _engineStatus: MutableStateFlow<Ipn.EngineStatus?> = MutableStateFlow(null)
    private val _tailFSShares: MutableStateFlow<Map<String, String>?> = MutableStateFlow(null)
    private val _browseToURL: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _loginFinished: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _version: MutableStateFlow<String?> = MutableStateFlow(null)

    private val _loggedInUser: MutableStateFlow<IpnLocal.LoginProfile?> = MutableStateFlow(null)
    private val _loginProfiles: MutableStateFlow<List<IpnLocal.LoginProfile>?> =
        MutableStateFlow(null)


    val state: StateFlow<Ipn.State> = _state
    val netmap: StateFlow<Netmap.NetworkMap?> = _netmap
    val prefs: StateFlow<Ipn.Prefs?> = _prefs
    val engineStatus: StateFlow<Ipn.EngineStatus?> = _engineStatus
    val tailFSShares: StateFlow<Map<String, String>?> = _tailFSShares
    val browseToURL: StateFlow<String?> = _browseToURL
    val loginFinished: StateFlow<String?> = _loginFinished
    val version: StateFlow<String?> = _version
    val loggedInUser: StateFlow<IpnLocal.LoginProfile?> = _loggedInUser
    val loginProfiles: StateFlow<List<IpnLocal.LoginProfile>?> = _loginProfiles

    val isUsingExitNode: Boolean
        get() {
            return prefs.value != null
        }


    // Backend Observation

    private suspend fun loadUserProfiles() {
        LocalApiClient.isReady.await()

        apiClient.getProfiles { result ->
            result.success?.let { users -> _loginProfiles.value = users }
                ?: run { Log.e("IpnManager", "Error loading profiles: ${result.error}") }
        }

        apiClient.getCurrentProfile { result ->
            result.success?.let { user -> _loggedInUser.value = user }
                ?: run { Log.e("IpnManager", "Error loading profiles: ${result.error}") }
        }
    }

    private fun onNotifyChange(notify: Ipn.Notify) {
        notify.State?.let { state ->
            Log.d("IpnModel", "State changed: $state")
            _state.value = Ipn.State.fromInt(state)
        }

        notify.NetMap?.let { netmap -> _netmap.value = netmap }

        notify.Prefs?.let { prefs -> _prefs.value = prefs }

        notify.Engine?.let { engine -> _engineStatus.value = engine }

        notify.TailFSShares?.let { shares -> _tailFSShares.value = shares }

        notify.BrowseToURL?.let { url -> _browseToURL.value = url }

        notify.LoginFinished?.let { message -> _loginFinished.value = message.property }

        notify.Version?.let { version -> _version.value = version }
    }

    init {
        Log.d("IpnModel", "IpnModel created")
        val session = notifier.watchAll { n -> onNotifyChange(n) }
        notifierSessions.add(session)
        scope.launch { loadUserProfiles() }
    }
}
