// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.content.Intent
import android.net.VpnService
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.ui.controller.ExitNodeController
import com.tailscale.ipn.ui.controller.PeerListController
import com.tailscale.ipn.ui.controller.PendingTaildropController
import com.tailscale.ipn.ui.dataSource.ExitNodeStatusDataSource
import com.tailscale.ipn.ui.dataSource.LoginActionsDataSource
import com.tailscale.ipn.ui.dataSource.MainDataSource
import com.tailscale.ipn.ui.model.Favorites
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Ipn.State
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.repository.ProfilesRepository
import com.tailscale.ipn.ui.util.TimeUtil
import com.tailscale.ipn.util.TSLog
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class MainViewModelFactory(
    private val appViewModel: AppViewModel,
    private val dataSource: MainDataSource,
    private val loginActions: LoginActionsDataSource,
    private val exitNodeDataSource: ExitNodeStatusDataSource,
    private val profilesRepository: ProfilesRepository,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
      return MainViewModel(
          appViewModel = appViewModel,
          dataSource = dataSource,
          loginActions = loginActions,
          exitNodeDataSource = exitNodeDataSource,
          profilesRepository = profilesRepository,
      )
          as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}

class MainViewModel(
    private val appViewModel: AppViewModel,
    private val dataSource: MainDataSource,
    private val loginActions: LoginActionsDataSource,
    exitNodeDataSource: ExitNodeStatusDataSource,
    profilesRepository: ProfilesRepository,
) : ViewModel() {
  val favorites: StateFlow<Favorites?> = dataSource.favorites
  val prefs = dataSource.prefs
  val netmap = dataSource.netmap
  val managedByOrganization = dataSource.managedByOrganization
  val healthIcon: StateFlow<Int?> = dataSource.healthIcon

  private val exitNodeController =
      ExitNodeController(
          dataSource = exitNodeDataSource,
          scope = viewModelScope,
      )

  val nodeState = exitNodeController.nodeState

  val ipnState = Notifier.state
  val isVpnPrepared = appViewModel.vpnPrepared
  private val isVpnActive = appViewModel.vpnActive

  private val _stateRes = MutableStateFlow(userStringRes(State.NoState, State.NoState, true))
  val stateRes: StateFlow<Int> = _stateRes.asStateFlow()

  private val _vpnToggleState = MutableStateFlow(false)
  val vpnToggleState: StateFlow<Boolean> = _vpnToggleState.asStateFlow()

  private val _isToggleInProgress = MutableStateFlow(false)
  val isToggleInProgress: StateFlow<Boolean> = _isToggleInProgress.asStateFlow()

  private val _requestVpnPermission = MutableStateFlow(false)
  val requestVpnPermission: StateFlow<Boolean> = _requestVpnPermission.asStateFlow()

  var autoFocusSearch by mutableStateOf(true)
    private set

  // True if we should render the key expiry banner
  private val _showExpiry = MutableStateFlow(false)
  val showExpiry: StateFlow<Boolean> = _showExpiry.asStateFlow()

  private val _pingPeer = MutableStateFlow<Tailcfg.Node?>(null)
  val pingPeer: StateFlow<Tailcfg.Node?> = _pingPeer.asStateFlow()

  val loggedInUser = profilesRepository.currentProfile

  // Self-contained controller for the Taildrop in-app banner/list sheet.
  val pendingTaildrop = PendingTaildropController(viewModelScope)

  private var vpnPermissionLauncher: ActivityResultLauncher<Intent>? = null

  private val peerListController =
      PeerListController(
          netmap = dataSource.netmap,
          favorites = dataSource.favorites,
          scope = viewModelScope,
      )

  val peers = peerListController.peers
  val searchViewPeers = peerListController.searchViewPeers
  val searchTerm = peerListController.searchTerm

  // Returns true if we should skip all of the user-interactive permissions prompts
  // (with the exception of the VPN permission prompt)
  fun skipPromptsForAuthKeyLogin(): Boolean = dataSource.skipPromptsForAuthKeyLogin()

  init {
    viewModelScope.launch {
      var previousState: State? = null

      combine(ipnState, isVpnActive) { state, active -> state to active }
          .collect { (currentState, active) ->
            // Determine the correct state resource string
            _stateRes.value = userStringRes(currentState, previousState, active)
            // Determine if the VPN toggle should be on
            _vpnToggleState.value =
                when {
                  active && (currentState == State.Running || currentState == State.Starting) ->
                      true

                  previousState == State.NoState && currentState == State.Starting -> true

                  else -> false
                }
            // Update the previous state
            previousState = currentState
          }
    }

    // Key expiry
    viewModelScope.launch {
      dataSource.netmap.filterNotNull().collect { netmap ->
        if (netmap.SelfNode.keyDoesNotExpire) {
          _showExpiry.value = false
        } else {
          val expiryNotificationWindowMDM = dataSource.keyExpirationNotice()
          val window =
              expiryNotificationWindowMDM?.let { TimeUtil.duration(it) } ?: Duration.ofHours(24)
          _showExpiry.value =
              TimeUtil.isWithinExpiryNotificationWindow(window, netmap.SelfNode.KeyExpiry ?: "")
        }
      }
    }
  }

  fun login(
      maskedPrefs: Ipn.MaskedPrefs? = null,
      authKey: String? = null,
      completionHandler: (Result<Unit>) -> Unit = {},
  ) {
    loginActions.login(
        scope = viewModelScope,
        maskedPrefs = maskedPrefs,
        authKey = authKey,
        completionHandler = completionHandler,
    )
  }

  fun updateSearchTerm(term: String) {
    peerListController.updateSearchTerm(term)
  }

  fun togglePin(peer: Tailcfg.Node) {
    dataSource.toggleFavorite(peer.StableID)
  }

  fun startPing(peer: Tailcfg.Node) {
    _pingPeer.value = peer
  }

  fun onPingDismissal() {
    _pingPeer.value = null
  }

  // Exit Node Manipulation

  suspend fun toggleExitNode() {
    exitNodeController.toggleExitNode()
  }

  suspend fun setRunningExitNode(isOn: Boolean) {
    exitNodeController.setRunningExitNode(isOn)
  }

  fun maybeRequestVpnPermission() {
    _requestVpnPermission.value = true
  }

  fun showVPNPermissionLauncherIfUnauthorized() {
    val vpnIntent = VpnService.prepare(App.get())
    TSLog.d("VpnPermissions", "vpnIntent=$vpnIntent")

    if (vpnIntent != null) {
      vpnPermissionLauncher?.launch(vpnIntent)
    } else {
      appViewModel.setVpnPrepared(true)
      UninitializedApp.get().startVPN()
    }
    _requestVpnPermission.value = false // reset
  }

  fun toggleVpn(desiredState: Boolean) {
    if (isToggleInProgress.value) {
      // Prevent toggling while a previous toggle is in progress
      return
    }

    viewModelScope.launch {
      _isToggleInProgress.value = true
      try {
        val currentState = ipnState.value

        if (desiredState) {
          // User wants to turn ON the VPN
          if (currentState != State.Running) {
            showVPNPermissionLauncherIfUnauthorized()
          }
        } else {
          // User wants to turn OFF the VPN
          if (currentState == State.Running) {
            UninitializedApp.get().stopVPN()
          }
        }
      } finally {
        _isToggleInProgress.value = false
      }
    }
  }

  fun enableSearchAutoFocus() {
    autoFocusSearch = true
  }

  fun disableSearchAutoFocus() {
    autoFocusSearch = false
  }

  fun setVpnPermissionLauncher(launcher: ActivityResultLauncher<Intent>) {
    // No intent means we're already authorized
    vpnPermissionLauncher = launcher
  }
}

private fun userStringRes(
    currentState: State?,
    previousState: State?,
    vpnActive: Boolean,
): Int {
  return when {
    previousState == State.NoState && currentState == State.Starting -> R.string.starting
    currentState == State.NoState -> R.string.placeholder
    currentState == State.InUseOtherUser -> R.string.placeholder
    currentState == State.NeedsLogin ->
        if (vpnActive) R.string.please_login else R.string.connect_to_vpn
    currentState == State.NeedsMachineAuth -> R.string.needs_machine_auth
    currentState == State.Stopped -> R.string.stopped
    currentState == State.Starting -> R.string.starting
    currentState == State.Running -> if (vpnActive) R.string.connected else R.string.placeholder
    else -> R.string.placeholder
  }
}
