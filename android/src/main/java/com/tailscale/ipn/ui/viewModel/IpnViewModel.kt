// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.dataSource.LoginActionsDataSource
import com.tailscale.ipn.ui.dataSource.LoginActionsDataSourceImpl
import com.tailscale.ipn.ui.dataSource.RunExitNodeDataSource
import com.tailscale.ipn.ui.dataSource.RunExitNodeDataSourceImpl
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.UserID
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
open class IpnViewModel(
    private val loginActionsDataSource: LoginActionsDataSource = LoginActionsDataSourceImpl(),
    private val runExitNodeDataSource: RunExitNodeDataSource = RunExitNodeDataSourceImpl(),
) : ViewModel() {
  protected val TAG = this::class.simpleName

  val loggedInUser: StateFlow<IpnLocal.LoginProfile?> = MutableStateFlow(null)
  val loginProfiles: StateFlow<List<IpnLocal.LoginProfile>?> = MutableStateFlow(null)

  private val _vpnPrepared = MutableStateFlow(false)
  val vpnPrepared: StateFlow<Boolean> = _vpnPrepared

  // The userId associated with the current node. ie: The logged-in user.
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
    RUNNING_AS_EXIT_NODE,
  }

  init {
    viewModelScope.launch {
      Notifier.state.collect {
        // Reload the user profiles on all state transitions to ensure loggedInUser is
        // correct
        viewModelScope.launch { loadUserProfiles() }
      }
    }

    // This will observe the userId of the current node and reload our user profiles if
    // we discover it has changed (e.g. due to a login or user switch)
    viewModelScope.launch {
      Notifier.netmap.collect { netmap ->
        netmap?.SelfNode?.User.let {
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

    viewModelScope.launch {
      combine(prefs, netmap, isRunningExitNode) { prefs, netmap, isRunningExitNode ->
            // Handle nullability for prefs and netmap
            val validPrefs = prefs ?: return@combine NodeState.NONE
            val validNetmap = netmap ?: return@combine NodeState.NONE

            val chosenExitNodeId = validPrefs.activeExitNodeID ?: validPrefs.selectedExitNodeID
            val exitNodePeer = chosenExitNodeId?.let { id ->
              validNetmap.Peers?.find { it.StableID == id }
            }

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
              isRunningExitNode -> {
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
   * 1. editPrefs() with maskedPrefs (to allow ControlURL override), LoggedOut=false if AuthKey !=
   *    null
   * 2. start() starts the LocalBackend state machine with WantRunning=true. to avoid a race: if
   *    editPrefs() set WantRunning=true, the backend would fire a cc.Login(LoginDefault) on the
   *    existing control client immediately. start() then tears down this client and creates a new
   *    one
   * 3. startLoginInteractive() is currently required for both interactive and non-interactive
   *    (using auth key) login
   *
   * Any failure short‑circuits the chain and invokes completionHandler once.
   */
  fun login(
      maskedPrefs: Ipn.MaskedPrefs? = null,
      authKey: String? = null,
      completionHandler: (Result<Unit>) -> Unit = {},
  ) {
    loginActionsDataSource.login(
        scope = viewModelScope,
        maskedPrefs = maskedPrefs,
        authKey = authKey,
        completionHandler = completionHandler,
    )
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
    val currentPrefs = lastPrefs ?: return

    LoadingIndicator.start()
    runExitNodeDataSource.setRunningExitNode(
        scope = viewModelScope,
        currentPrefs = currentPrefs,
        isOn = isOn,
    ) { result ->
      LoadingIndicator.stop()
      TSLog.d(TAG, "Edited prefs: $result")
    }
  }
}
