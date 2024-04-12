// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.IPNReceiver
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.UserID
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
  protected val TAG = this::class.simpleName

  val loggedInUser: StateFlow<IpnLocal.LoginProfile?> = MutableStateFlow(null)
  val loginProfiles: StateFlow<List<IpnLocal.LoginProfile>?> = MutableStateFlow(null)

  // The userId associated with the current node. ie: The logged in user.
  private var selfNodeUserId: UserID? = null

  init {
    viewModelScope.launch {
      Notifier.state.collect {
        // Reload the user profiles on all state transitions to ensure loggedInUser is correct
        viewModelScope.launch { loadUserProfiles() }
      }
    }

    // This will observe the userId of the current node and reload our user profiles if
    // we discover it has changed (e.g. due to a login or user switch)
    viewModelScope.launch {
      Notifier.netmap.collect {
        it?.SelfNode?.User.let {
          if (it != selfNodeUserId) {
            selfNodeUserId = it
            viewModelScope.launch { loadUserProfiles() }
          }
        }
      }
    }

    viewModelScope.launch { loadUserProfiles() }
    Log.d(TAG, "Created")
  }

  // VPN Control

  fun toggleVpn() {
    when (Notifier.state.value) {
      Ipn.State.Running -> stopVPN()
      else -> startVPN()
    }
  }

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

  // Login/Logout

  fun login(options: Ipn.Options = Ipn.Options(), completionHandler: (Result<Unit>) -> Unit = {}) {

    val loginAction = {
      Client(viewModelScope).startLoginInteractive { result ->
        result
            .onSuccess { Log.d(TAG, "Login started: $it") }
            .onFailure { Log.e(TAG, "Error starting login: ${it.message}") }
        completionHandler(result)
      }
    }

    Client(viewModelScope).start(options) { start ->
      start.onFailure { completionHandler(Result.failure(it)) }.onSuccess { loginAction() }
    }
  }

  fun loginWithAuthKey(authKey: String, completionHandler: (Result<Unit>) -> Unit = {}) {
    val prefs = Notifier.prefs.value
    if (prefs == null) {
      completionHandler(Result.failure(Error("no prefs")))
      return
    }

    prefs.WantRunning = true
    val options = Ipn.Options(AuthKey = authKey, UpdatePrefs = prefs)
    login(options, completionHandler)
  }

  fun loginWithCustomControlURL(
      controlURL: String,
      completionHandler: (Result<Unit>) -> Unit = {}
  ) {
    val fail: (Throwable) -> Unit = { completionHandler(Result.failure(it)) }

    // We need to have the current prefs to set them back with the new control URL
    val prefs = Notifier.prefs.value
    if (prefs == null) {
      fail(Error("no prefs"))
      return
    }

    // The flow for logging in with a custom control URL is to add a profile,
    // call start with prefs that include the control URL, then
    // start an interactive login.
    Client(viewModelScope).addProfile { addProfile ->
      addProfile.onFailure(fail).onSuccess {
        prefs.ControlURL = controlURL
        val options = Ipn.Options(UpdatePrefs = prefs)
        login(options, completionHandler)
      }
    }
  }

  fun logout(completionHandler: (Result<String>) -> Unit = {}) {
    Client(viewModelScope).logout { result ->
      result
          .onSuccess { Log.d(TAG, "Logout started: $it") }
          .onFailure { Log.e(TAG, "Error starting logout: ${it.message}") }
      completionHandler(result)
    }
  }

  // User Profiles

  private fun loadUserProfiles() {
    Client(viewModelScope).profiles { result ->
      result.onSuccess(loginProfiles::set).onFailure {
        Log.e(TAG, "Error loading profiles: ${it.message}")
      }
    }

    Client(viewModelScope).currentProfile { result ->
      result
          .onSuccess { loggedInUser.set(if (it.isEmpty()) null else it) }
          .onFailure { Log.e(TAG, "Error loading current profile: ${it.message}") }
    }
  }

  fun switchProfile(profile: IpnLocal.LoginProfile, completionHandler: (Result<String>) -> Unit) {
    Client(viewModelScope).switchProfile(profile) {
      startVPN()
      completionHandler(it)
    }
  }

  fun addProfile(completionHandler: (Result<String>) -> Unit) {
    Client(viewModelScope).addProfile {
      if (it.isSuccess) {
        login()
      }
      startVPN()
      completionHandler(it)
    }
  }

  fun deleteProfile(profile: IpnLocal.LoginProfile, completionHandler: (Result<String>) -> Unit) {
    Client(viewModelScope).deleteProfile(profile) {
      viewModelScope.launch { loadUserProfiles() }
      completionHandler(it)
    }
  }
}
