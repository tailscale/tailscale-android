// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.UserID
import com.tailscale.ipn.ui.model.deepCopy
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.AdvertisedRoutesHelper
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Base model for most models in this application. Provides common facilities for watching IPN
 * notifications, managing login/logout, updating preferences, etc.
 */
open class IpnViewModel : ViewModel() {
  protected val TAG = this::class.simpleName

  val loggedInUser: StateFlow<IpnLocal.LoginProfile?> = MutableStateFlow(null)
  val loginProfiles: StateFlow<List<IpnLocal.LoginProfile>?> = MutableStateFlow(null)

  private val _vpnPrepared = MutableStateFlow(false)
  val vpnPrepared: StateFlow<Boolean> = _vpnPrepared

  // The userId associated with the current node. ie: The logged in user.
  private var selfNodeUserId: UserID? = null

  val isRunningExitNode: StateFlow<Boolean> = MutableStateFlow(false)
  private var lastPrefs: Ipn.Prefs? = null

  val prefs = Notifier.prefs
  val netmap = Notifier.netmap
  private val _nodeState = MutableStateFlow(NodeState.NONE)
  val nodeState: StateFlow<NodeState> = _nodeState
  val managedByOrganization = MDMSettings.managedByOrganizationName.flow

  enum class NodeState {
    NONE,
    ACTIVE_AND_RUNNING,
    // Last selected exit node is active but is not being used.
    ACTIVE_NOT_RUNNING,
    // Last selected exit node is currently offline.
    OFFLINE_ENABLED,
    // Last selected exit node has been de-selected and is currently offline.
    OFFLINE_DISABLED,
    // Exit node selection is managed by an administrator, and last selected exit node is currently
    // offline
    OFFLINE_MDM,
    RUNNING_AS_EXIT_NODE
  }

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

    viewModelScope.launch {
      Notifier.prefs.collect {
        it?.let {
          lastPrefs = it
          isRunningExitNode.set(AdvertisedRoutesHelper.exitNodeOnFromPrefs(it))
        }
      }
    }

    viewModelScope.launch { loadUserProfiles() }

    viewModelScope.launch {
      combine(prefs, netmap, isRunningExitNode) { prefs, netmap, isRunningExitNode ->
            // Handle nullability for prefs and netmap
            val validPrefs = prefs ?: return@combine NodeState.NONE
            val validNetmap = netmap ?: return@combine NodeState.NONE

            val chosenExitNodeId = validPrefs.activeExitNodeID ?: validPrefs.selectedExitNodeID
            val exitNodePeer =
                chosenExitNodeId?.let { id -> validNetmap.Peers?.find { it.StableID == id } }

            when {
              exitNodePeer?.Online == false -> {
                if (MDMSettings.exitNodeID.flow.value.value != null) {
                  NodeState.OFFLINE_MDM
                } else if (validPrefs.activeExitNodeID != null) {
                  NodeState.OFFLINE_ENABLED
                } else {
                  NodeState.OFFLINE_DISABLED
                }
              }
              exitNodePeer != null -> {
                if (!validPrefs.activeExitNodeID.isNullOrEmpty()) {
                  NodeState.ACTIVE_AND_RUNNING
                } else {
                  NodeState.ACTIVE_NOT_RUNNING
                }
              }
              isRunningExitNode == true -> {
                NodeState.RUNNING_AS_EXIT_NODE
              }
              else -> {
                NodeState.NONE
              }
            }
          }
          .collect { nodeState -> _nodeState.value = nodeState }
    }
    TSLog.d(TAG, "Created")
  }

  // VPN Control
  fun startVPN() {
    UninitializedApp.get().startVPN()
  }

  fun stopVPN() {
    UninitializedApp.get().stopVPN()
  }

  // Login/Logout

  /**
   * Order of operations:
   * 1. editPrefs() with maskedPrefs (to allow ControlURL override), WantRunning=true, LoggedOut=false if AuthKey != null  
   * 2. start() starts the LocalBackend state machine
   * 3. startLoginInteractive() is currently required for bother interactive and non-interactive (using auth key) login
   *
   * Any failure short‑circuits the chain and invokes completionHandler once.
   */
  fun login(
      maskedPrefs: Ipn.MaskedPrefs? = null,
      authKey: String? = null,
      completionHandler: (Result<Unit>) -> Unit = {}
  ) {
    val client = Client(viewModelScope)

    val finalMaskedPrefs = maskedPrefs?.deepCopy() ?: Ipn.MaskedPrefs()
    finalMaskedPrefs.WantRunning = true
    if (authKey != null) {
      finalMaskedPrefs.LoggedOut = false
    }

    client.editPrefs(finalMaskedPrefs) { editResult ->
      editResult
          .onFailure {
            TSLog.e(TAG, "editPrefs() failed: ${it.message}")
            completionHandler(Result.failure(it))
          }
          .onSuccess {
            val opts = Ipn.Options(UpdatePrefs = editResult.getOrThrow(), AuthKey = authKey)
            client.start(opts) { startResult ->
              startResult
                  .onFailure {
                    TSLog.e(TAG, "start() failed: ${it.message}")
                    completionHandler(Result.failure(it))
                  }
                  .onSuccess {
                    client.startLoginInteractive { loginResult ->
                      loginResult
                          .onFailure {
                            TSLog.e(TAG, "startLoginInteractive() failed: ${it.message}")
                            completionHandler(Result.failure(it))
                          }
                          .onSuccess { completionHandler(Result.success(Unit)) }
                    }
                  }
            }
          }
    }
  }

  fun loginWithAuthKey(authKey: String, completionHandler: (Result<Unit>) -> Unit = {}) {
    val prefs = Ipn.MaskedPrefs()
    prefs.WantRunning = true
    login(prefs, authKey = authKey, completionHandler)
  }

  fun loginWithCustomControlURL(
      controlURL: String,
      completionHandler: (Result<Unit>) -> Unit = {}
  ) {
    val prefs = Ipn.MaskedPrefs()
    prefs.ControlURL = controlURL
    login(prefs, completionHandler = completionHandler)
  }

  fun logout(completionHandler: (Result<String>) -> Unit = {}) {
    Client(viewModelScope).logout { result ->
      result
          .onSuccess { TSLog.d(TAG, "Logout started: $it") }
          .onFailure { TSLog.e(TAG, "Error starting logout: ${it.message}") }
      completionHandler(result)
    }
  }

  // User Profiles

  private fun loadUserProfiles() {
    Client(viewModelScope).profiles { result ->
      result.onSuccess(loginProfiles::set).onFailure {
        TSLog.e(TAG, "Error loading profiles: ${it.message}")
      }
    }

    Client(viewModelScope).currentProfile { result ->
      result
          .onSuccess { loggedInUser.set(if (it.isEmpty()) null else it) }
          .onFailure { TSLog.e(TAG, "Error loading current profile: ${it.message}") }
    }
  }

  fun switchProfile(profile: IpnLocal.LoginProfile, completionHandler: (Result<String>) -> Unit) {
    val switchProfile = {
      Client(viewModelScope).switchProfile(profile) {
        startVPN()
        completionHandler(it)
      }
    }
    Client(viewModelScope).editPrefs(Ipn.MaskedPrefs().apply { WantRunning = false }) { result ->
      result
          .onSuccess { switchProfile() }
          .onFailure { TSLog.e(TAG, "Error setting wantRunning to false: ${it.message}") }
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

  // Exit Node Manipulation

  fun toggleExitNode() {
    val prefs = prefs.value ?: return

    LoadingIndicator.start()
    if (prefs.activeExitNodeID != null) {
      // We have an active exit node so we should keep it, but disable it
      Client(viewModelScope).setUseExitNode(false) { LoadingIndicator.stop() }
    } else if (prefs.selectedExitNodeID != null) {
      // We have a prior exit node to enable
      Client(viewModelScope).setUseExitNode(true) { LoadingIndicator.stop() }
    } else {
      // This should not be possible.  In this state the button is hidden
      TSLog.e(TAG, "No exit node to disable and no prior exit node to enable")
    }
  }

  fun setRunningExitNode(isOn: Boolean) {
    LoadingIndicator.start()
    lastPrefs?.let { currentPrefs ->
      val newPrefs: Ipn.MaskedPrefs
      if (isOn) {
        newPrefs = setZeroRoutes(currentPrefs)
      } else {
        newPrefs = removeAllZeroRoutes(currentPrefs)
      }
      Client(viewModelScope).editPrefs(newPrefs) { result ->
        LoadingIndicator.stop()
        TSLog.d("RunExitNodeViewModel", "Edited prefs: $result")
      }
    }
  }

  private fun setZeroRoutes(prefs: Ipn.Prefs): Ipn.MaskedPrefs {
    val newRoutes = (removeAllZeroRoutes(prefs).AdvertiseRoutes ?: emptyList()).toMutableList()
    newRoutes.add("0.0.0.0/0")
    newRoutes.add("::/0")
    val newPrefs = Ipn.MaskedPrefs()
    newPrefs.AdvertiseRoutes = newRoutes
    return newPrefs
  }

  private fun removeAllZeroRoutes(prefs: Ipn.Prefs): Ipn.MaskedPrefs {
    val newRoutes = emptyList<String>().toMutableList()
    (prefs.AdvertiseRoutes ?: emptyList()).forEach {
      if (it != "0.0.0.0/0" && it != "::/0") {
        newRoutes.add(it)
      }
    }
    val newPrefs = Ipn.MaskedPrefs()
    newPrefs.AdvertiseRoutes = newRoutes
    return newPrefs
  }
}
