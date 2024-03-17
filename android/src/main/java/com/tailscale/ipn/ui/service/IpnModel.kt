// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.service

import android.util.Log
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class IpnModel(notifier: Notifier, val scope: CoroutineScope) {
    companion object {
        private const val TAG = "IpnModel"
    }

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
        Client(scope).profiles { result ->
            result.onSuccess(loginProfiles::set).onFailure {
                Log.e(TAG, "Error loading profiles: ${it.message}")
            }
        }

        Client(scope).currentProfile { result ->
            result.onSuccess(loggedInUser::set).onFailure {
                Log.e(TAG, "Error loading current profile: ${it.message}")
            }
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
