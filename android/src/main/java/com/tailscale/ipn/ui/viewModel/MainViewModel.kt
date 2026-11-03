// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.ui.viewModel

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Favorites
import com.tailscale.ipn.ui.model.Ipn.State
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.PeerCategorizer
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.TimeUtil
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.util.withPinnedSection
import com.tailscale.ipn.util.TSLog
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainViewModelFactory(private val appViewModel: AppViewModel) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
      return MainViewModel(appViewModel) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}

@OptIn(FlowPreview::class)
class MainViewModel(private val appViewModel: AppViewModel) : IpnViewModel() {
  // The user readable state of the system
  val stateRes: StateFlow<Int> = MutableStateFlow(userStringRes(State.NoState, State.NoState, true))

  // The expected state of the VPN toggle
  private val _vpnToggleState = MutableStateFlow(false)
  val vpnToggleState: StateFlow<Boolean> = _vpnToggleState

  // Keeps track of whether a toggle operation is in progress. This ensures that toggleVpn cannot be
  // invoked until the current operation is complete.
  private val _isToggleInProgress = MutableStateFlow(false)
  val isToggleInProgress: StateFlow<Boolean> = _isToggleInProgress

  companion object {
    /**
     * How long a VPN toggle keeps the switch disabled while waiting for the backend to settle in
     * the requested state. Toggling again while a start/stop is in flight cancels and restarts
     * backend work (see tailscale/tailscale#18304), so the switch stays disabled (and the main view
     * shows the transition state) until the IPN state reaches a stable state.
     */
    private const val TOGGLE_SETTLE_TIMEOUT_MS = 30_000L
  }

  // Permission to prepare VPN
  private var vpnPermissionLauncher: ActivityResultLauncher<Intent>? = null
  private val _requestVpnPermission = MutableStateFlow(false)
  val requestVpnPermission: StateFlow<Boolean> = _requestVpnPermission

  // Select Taildrop directory
  private var directoryPickerLauncher: ActivityResultLauncher<Uri?>? = null

  // The list of peers
  private val _peers = MutableStateFlow<List<PeerSet>>(emptyList())
  val peers: StateFlow<List<PeerSet>> = _peers

  private val _groupedPeers = MutableStateFlow<List<PeerSet>>(emptyList())

  // The list of peers
  private val _searchViewPeers = MutableStateFlow<List<PeerSet>>(emptyList())
  val searchViewPeers: StateFlow<List<PeerSet>> = _searchViewPeers

  // The current state of the IPN for determining view visibility
  val ipnState = Notifier.state

  private val favoritesManager = App.get().favoritesManager
  val favorites: StateFlow<Favorites?> = favoritesManager.favorites
  val isWritingFavorites: StateFlow<Boolean> = favoritesManager.writing

  // The active search term for filtering peers
  private val _searchTerm = MutableStateFlow("")
  val searchTerm: StateFlow<String> = _searchTerm
  var autoFocusSearch by mutableStateOf(true)
    private set

  // True if we should render the key expiry banner
  val showExpiry: StateFlow<Boolean> = MutableStateFlow(false)

  // The peer for which the dropdown menu is currently expanded. Null if no menu is expanded
  var expandedMenuPeer: StateFlow<Tailcfg.Node?> = MutableStateFlow(null)

  var pingViewModel: PingViewModel = PingViewModel()

  // Self-contained controller for the Taildrop in-app banner/list sheet.
  val pendingTaildrop: PendingTaildropViewModel = PendingTaildropViewModel()

  val isVpnPrepared: StateFlow<Boolean> = appViewModel.vpnPrepared

  val isVpnActive: StateFlow<Boolean> = appViewModel.vpnActive

  private var searchJob: Job? = null

  // Icon displayed in the button to present the health view
  val healthIcon: StateFlow<Int?> = MutableStateFlow(null)

  fun updateSearchTerm(term: String) {
    _searchTerm.value = term
  }

  fun hidePeerDropdownMenu() {
    expandedMenuPeer.set(null)
  }

  fun copyIPV4Address(peer: Tailcfg.Node, clipboardManager: ClipboardManager) {
    clipboardManager.setText(AnnotatedString(peer.primaryIPv4Address ?: ""))
  }

  fun copyIPV6Address(peer: Tailcfg.Node, clipboardManager: ClipboardManager) {
    clipboardManager.setText(AnnotatedString(peer.primaryIPv6Address ?: ""))
  }

  fun copyMagicDNSAddress(peer: Tailcfg.Node, clipboardManager: ClipboardManager) {
    clipboardManager.setText(AnnotatedString(peer.magicDNSAddress ?: ""))
  }

  fun togglePin(peer: Tailcfg.Node) {
    favoritesManager.toggleDevice(peer.StableID)
  }

  fun startPing(peer: Tailcfg.Node) {
    this.pingViewModel.startPing(peer)
  }

  fun onPingDismissal() {
    this.pingViewModel.handleDismissal()
  }

  // Returns true if we should skip all of the user-interactive permissions prompts
  // (with the exception of the VPN permission prompt)
  fun skipPromptsForAuthKeyLogin(): Boolean {
    val v = MDMSettings.authKey.flow.value.value
    return v != null && v != ""
  }

  private val peerCategorizer = PeerCategorizer()
  @OptIn(ExperimentalCoroutinesApi::class)
  private val categorizerDispatcher = Dispatchers.Default.limitedParallelism(1)

  init {
    viewModelScope.launch {
      var previousState: State? = null
      combine(Notifier.state, isVpnActive) { state, active -> state to active }
          .collect { (currentState, active) ->
            // Determine the correct state resource string
            stateRes.set(userStringRes(currentState, previousState, active))
            // Determine if the VPN toggle should be on
            val isOn =
                when {
                  active && (currentState == State.Running || currentState == State.Starting) ->
                      true
                  previousState == State.NoState && currentState == State.Starting -> true
                  else -> false
                }
            // Update the VPN toggle state
            _vpnToggleState.value = isOn
            // Update the previous state
            previousState = currentState
          }
    }

    viewModelScope.launch {
      _searchTerm.debounce(250L.milliseconds).collect { term ->
        // run the search as a background task
        searchJob?.cancel()
        searchJob =
            launch(categorizerDispatcher) {
              val filteredPeers = peerCategorizer.groupedAndFilteredPeers(term)
              _searchViewPeers.value = filteredPeers
            }
      }
    }

    // handle grouping
    viewModelScope.launch {
      Notifier.netmap.filterNotNull().collectLatest { netmap ->
        searchJob?.cancel()
        withContext(categorizerDispatcher) {
          peerCategorizer.regenerateGroupedPeers(netmap)
          val filteredPeers = peerCategorizer.groupedAndFilteredPeers(searchTerm.value)
          _groupedPeers.value = peerCategorizer.peerSets
          _searchViewPeers.value = filteredPeers
        }
      }
    }

    // transform with favorites
    viewModelScope.launch {
      combine(_groupedPeers, favorites) { sets, favs -> sets to favs }
          .collectLatest { (sets, favs) ->
            withContext(categorizerDispatcher) {
              _peers.value = sets.withPinnedSection(favs?.deviceIds.orEmpty())
            }
          }
    }

    // Key expiry
    viewModelScope.launch {
      Notifier.netmap.filterNotNull().collect { netmap ->
        if (netmap.SelfNode.keyDoesNotExpire) {
          showExpiry.set(false)
        } else {
          val expiryNotificationWindowMDM = MDMSettings.keyExpirationNotice.flow.value.value
          val window =
              expiryNotificationWindowMDM?.let { TimeUtil.duration(it) } ?: Duration.ofHours(24)
          showExpiry.set(
              TimeUtil.isWithinExpiryNotificationWindow(
                  window,
                  netmap.SelfNode.KeyExpiry ?: "",
              )
          )
        }
      }
    }
    viewModelScope.launch {
      App.get().healthNotifier?.currentIcon?.collect { icon -> healthIcon.set(icon) }
    }
  }

  fun maybeRequestVpnPermission() {
    _requestVpnPermission.value = true
  }

  /**
   * Shows the VPN permission launcher if permission has not been granted yet. Returns true if a
   * system permission dialog was shown (in which case the caller waits for the dialog instead of
   * for the VPN state to change).
   */
  fun showVPNPermissionLauncherIfUnauthorized(): Boolean {
    val vpnIntent = VpnService.prepare(App.get())
    TSLog.d("VpnPermissions", "vpnIntent=$vpnIntent")
    if (vpnIntent != null) {
      vpnPermissionLauncher?.launch(vpnIntent)
      _requestVpnPermission.value = false // reset
      return true
    }
    appViewModel.setVpnPrepared(true)
    startVPN()
    _requestVpnPermission.value = false // reset
    return false
  }

  /**
   * Turns the VPN on or off. While the resulting start/stop is in flight the switch stays disabled
   * (see [isToggleInProgress]) and the main view shows the transition state ("Starting"/ "Not
   * connected"), until the IPN state settles in a stable state or [TOGGLE_SETTLE_TIMEOUT_MS]
   * passes. If a permission dialog is shown, the guard is released instead: the dialog itself
   * prevents a second toggle, and the user must answer it first.
   */
  fun toggleVpn(desiredState: Boolean) {
    if (isToggleInProgress.value) {
      // Prevent toggling while a previous toggle is in progress
      return
    }

    viewModelScope.launch {
      _isToggleInProgress.value = true
      val startState = Notifier.state.value
      try {
        var permissionDialogShown = false
        var transitionRequested = false

        if (desiredState) {
          // User wants to turn ON the VPN
          if (startState != State.Running) {
            transitionRequested = true
            permissionDialogShown = showVPNPermissionLauncherIfUnauthorized()
          }
        } else {
          // User wants to turn OFF the VPN
          if (startState == State.Running) {
            transitionRequested = true
            stopVPN()
          }
        }

        if (transitionRequested && !permissionDialogShown) {
          // Wait for the IPN state to settle outside the transient states (Starting/Stopping) so
          // that a rapid second toggle cannot cancel the first transition's backend work. If a
          // permission dialog was shown, the dialog itself prevents a second toggle until it is
          // answered.
          withTimeoutOrNull(TOGGLE_SETTLE_TIMEOUT_MS) {
            Notifier.state.first { state ->
              val settled = state != State.Starting && state != State.Stopping
              settled && (state != startState || state == State.NeedsLogin)
            }
          }
        }
      } finally {
        _isToggleInProgress.value = false
      }
    }
  }

  fun searchPeers(searchTerm: String) {
    this.searchTerm.set(searchTerm)
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

private fun userStringRes(currentState: State?, previousState: State?, vpnActive: Boolean): Int {
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
