// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.service

import android.util.Log
import com.tailscale.ipn.ui.localapi.LocalApiClient
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Provides a way to expose a MutableStateFlow as an immutable StateFlow.
 */
fun <T> StateFlow<T>.set(v: T) {
    (this as MutableStateFlow<T>).value = v
}

class IpnModel(
        notifier: Notifier,
        val apiClient: LocalApiClient,
        val scope: CoroutineScope
) {
    private var notifierSessions: MutableList<String> = mutableListOf()

    val state: StateFlow<Ipn.State> = MutableStateFlow(Ipn.State.NoState)
    val netmap: StateFlow<Netmap.NetworkMap?> = MutableStateFlow(null)
    val prefs: StateFlow<Ipn.Prefs?> = MutableStateFlow(null)
    val engineStatus: StateFlow<Ipn.EngineStatus?> = MutableStateFlow(null)
    val tailFSShares: StateFlow<Map<String, String>?> = MutableStateFlow(null)
    val browseToURL: StateFlow<String?> = MutableStateFlow(null)
    val loginFinished: StateFlow<String?> = MutableStateFlow(null)
    val version: StateFlow<String?> = MutableStateFlow(null)
    val loggedInUser: StateFlow<IpnLocal.LoginProfile?> = MutableStateFlow(null)
    val loginProfiles: StateFlow<List<IpnLocal.LoginProfile>?> = MutableStateFlow(null)

    val isUsingExitNode: Boolean
        get() {
            return prefs.value != null
        }

    // Backend Observation

    private suspend fun loadUserProfiles() {
        LocalApiClient.isReady.await()

        apiClient.getProfiles { result ->
            result.success?.let(loginProfiles::set)
                    ?: run { Log.e("IpnManager", "Error loading profiles: ${result.error}") }
        }

        apiClient.getCurrentProfile { result ->
            result.success?.let(loggedInUser::set)
                    ?: run { Log.e("IpnManager", "Error loading current profile: ${result.error}") }
        }
    }

    private fun onNotifyChange(notify: Ipn.Notify) {
        notify.State?.let { s ->
            // Refresh the user profiles if we're transitioning out of the
            // NeedsLogin state.
            if (state.value == Ipn.State.NeedsLogin) {
                scope.launch { loadUserProfiles() }
            }

            Log.d("IpnModel", "State changed: $s")
            state.set(Ipn.State.fromInt(s))
        }

        notify.NetMap?.let(netmap::set)
        notify.Prefs?.let(prefs::set)
        notify.Engine?.let(engineStatus::set)
        notify.TailFSShares?.let(tailFSShares::set)
        notify.BrowseToURL?.let(browseToURL::set)
        notify.LoginFinished?.let { loginFinished.set(it.property) }
        notify.Version?.let(version::set)
    }

    init {
        Log.d("IpnModel", "IpnModel created")
        val session = notifier.watchAll { n -> onNotifyChange(n) }
        notifierSessions.add(session)
        scope.launch { loadUserProfiles() }
    }
}
