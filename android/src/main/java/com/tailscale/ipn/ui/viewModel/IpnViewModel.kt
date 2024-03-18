// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Base model for most models in this application. Provides common facilities for watching IPN
 * notifications, managing login/logout, updating preferences, etc.
 */
open class IpnViewModel : ViewModel() {
    companion object {
        val mdmSettings: StateFlow<MDMSettings> = MutableStateFlow(MDMSettings())
    }

    protected val TAG = this::class.simpleName
    val loggedInUser: StateFlow<IpnLocal.LoginProfile?> = MutableStateFlow(null)
    val loginProfiles: StateFlow<List<IpnLocal.LoginProfile>?> = MutableStateFlow(null)

    init {
        viewModelScope.launch {
            Notifier.state.collect {
                // Refresh the user profiles if we're transitioning out of the
                // NeedsLogin state.
                if (it == Ipn.State.NeedsLogin) {
                    viewModelScope.launch { loadUserProfiles() }
                }
            }
        }
        viewModelScope.launch { loadUserProfiles() }
        Log.d(TAG, "Created")
    }

    private fun loadUserProfiles() {
        Client(viewModelScope).profiles { result ->
            result.onSuccess(loginProfiles::set).onFailure {
                Log.e(TAG, "Error loading profiles: ${it.message}")
            }
        }

        Client(viewModelScope).currentProfile { result ->
            result.onSuccess(loggedInUser::set).onFailure {
                Log.e(TAG, "Error loading current profile: ${it.message}")
            }
        }
    }

    fun login() {
        Client(viewModelScope).startLoginInteractive { result ->
            result.onSuccess {
                Log.d(TAG, "Login started: $it")
            }.onFailure {
                Log.e(TAG, "Error starting login: ${it.message}")
            }
        }
    }

    fun logout() {
        Client(viewModelScope).logout { result ->
            result.onSuccess {
                Log.d(TAG, "Logout started: $it")
            }.onFailure {
                Log.e(TAG, "Error starting logout: ${it.message}")
            }
        }
    }

    // The below handle all types of preference modifications typically invoked by the UI.
    // Callers generally shouldn't care about the returned prefs value - the source of
    // truth is the IPNModel, who's prefs flow will change in value to reflect the true
    // value of the pref setting in the back end (and will match the value returned here).
    // Generally, you will want to inspect the returned value in the callback for errors
    // to indicate why a particular setting did not change in the interface.
    //
    // Usage:
    // - User/Interface changed to new value.  Render the new value.
    // - Submit the new value to the PrefsEditor
    // - Observe the prefs on the IpnModel and update the UI when/if the value changes.
    //   For a typical flow, the changed value should reflect the value already shown.
    // - Inform the user of any error which may have occurred
    //
    // The "toggle' functions here will attempt to set the pref value to the inverse of
    // what is currently known in the IpnModel.prefs.  If IpnModel.prefs is not available,
    // the callback will be called with a NO_PREFS error
    fun setWantRunning(wantRunning: Boolean, callback: (Result<Ipn.Prefs>) -> Unit) {
        Ipn.MaskedPrefs().WantRunning = wantRunning
        Client(viewModelScope).editPrefs(Ipn.MaskedPrefs(), callback)
    }

    fun toggleCorpDNS(callback: (Result<Ipn.Prefs>) -> Unit) {
        val prefs = Notifier.prefs.value ?: run {
            callback(Result.failure(Exception("no prefs")))
            return@toggleCorpDNS
        }

        val prefsOut = Ipn.MaskedPrefs()
        prefsOut.CorpDNS = !prefs.CorpDNS
        Client(viewModelScope).editPrefs(prefsOut, callback)
    }

    fun toggleShieldsUp(callback: (Result<Ipn.Prefs>) -> Unit) {
        val prefs = Notifier.prefs.value ?: run {
            callback(Result.failure(Exception("no prefs")))
            return@toggleShieldsUp
        }

        val prefsOut = Ipn.MaskedPrefs()
        prefsOut.ShieldsUp = !prefs.ShieldsUp
        Client(viewModelScope).editPrefs(prefsOut, callback)
    }

    fun toggleRouteAll(callback: (Result<Ipn.Prefs>) -> Unit) {
        val prefs = Notifier.prefs.value ?: run {
            callback(Result.failure(Exception("no prefs")))
            return@toggleRouteAll
        }

        val prefsOut = Ipn.MaskedPrefs()
        prefsOut.RouteAll = !prefs.RouteAll
        Client(viewModelScope).editPrefs(prefsOut, callback)
    }
}
