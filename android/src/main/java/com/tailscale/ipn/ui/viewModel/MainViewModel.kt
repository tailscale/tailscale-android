// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.content.Intent
import android.net.VpnService
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Ipn.State
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.PeerCategorizer
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.TimeUtil
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.time.Duration

class MainViewModelFactory(private val vpnViewModel: VpnViewModel) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
      return MainViewModel(vpnViewModel) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}

@OptIn(FlowPreview::class)
class MainViewModel(private val vpnViewModel: VpnViewModel) : IpnViewModel() {

  // The user readable state of the system
  val stateRes: StateFlow<Int> = MutableStateFlow(userStringRes(State.NoState, State.NoState, true))

  // The expected state of the VPN toggle
  private val _vpnToggleState = MutableStateFlow(false)
  val vpnToggleState: StateFlow<Boolean> = _vpnToggleState

  // Keeps track of whether a toggle operation is in progress. This ensures that toggleVpn cannot be
  // invoked until the current operation is complete.
  var isToggleInProgress = MutableStateFlow(false)

  // Permission to prepare VPN
  private var vpnPermissionLauncher: ActivityResultLauncher<Intent>? = null

  // The list of peers
  private val _peers = MutableStateFlow<List<PeerSet>>(emptyList())
  val peers: StateFlow<List<PeerSet>> = _peers

  // The current state of the IPN for determining view visibility
  val ipnState = Notifier.state

  // The active search term for filtering peers
  private val _searchTerm = MutableStateFlow("")
  val searchTerm: StateFlow<String> = _searchTerm

  // True if we should render the key expiry bannder
  val showExpiry: StateFlow<Boolean> = MutableStateFlow(false)

  // The peer for which the dropdown menu is currently expanded. Null if no menu is expanded
  var expandedMenuPeer: StateFlow<Tailcfg.Node?> = MutableStateFlow(null)

  var pingViewModel: PingViewModel = PingViewModel()

  val isVpnPrepared: StateFlow<Boolean> = vpnViewModel.vpnPrepared

  val isVpnActive: StateFlow<Boolean> = vpnViewModel.vpnActive

  var searchJob: Job? = null

  // Icon displayed in the button to present the health view
  val healthIcon: StateFlow<Int?> = MutableStateFlow(null)

  fun updateSearchTerm(term: String) {
    _searchTerm.value = term
  }

  fun hidePeerDropdownMenu() {
    expandedMenuPeer.set(null)
  }

  fun copyIpAddress(peer: Tailcfg.Node, clipboardManager: ClipboardManager) {
    clipboardManager.setText(AnnotatedString(peer.primaryIPv4Address ?: ""))
  }

  fun startPing(peer: Tailcfg.Node) {
    this.pingViewModel.startPing(peer)
  }

  fun onPingDismissal() {
    this.pingViewModel.handleDismissal()
  }

  private val peerCategorizer = PeerCategorizer()

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
      _searchTerm.debounce(250L).collect { term ->
        // run the search as a background task
        searchJob?.cancel()
        searchJob =
            launch(Dispatchers.Default) {
              val filteredPeers = peerCategorizer.groupedAndFilteredPeers(term)
              _peers.value = filteredPeers
            }
      }
    }

    viewModelScope.launch {
      Notifier.netmap.collect { it ->
        it?.let { netmap ->
          searchJob?.cancel()
          launch(Dispatchers.Default) {
            peerCategorizer.regenerateGroupedPeers(netmap)
            val filteredPeers = peerCategorizer.groupedAndFilteredPeers(searchTerm.value)
            _peers.value = filteredPeers
          }

          if (netmap.SelfNode.keyDoesNotExpire) {
            showExpiry.set(false)
            return@let
          } else {
            val expiryNotificationWindowMDM = MDMSettings.keyExpirationNotice.flow.value.value
            val window =
                expiryNotificationWindowMDM?.let { TimeUtil.duration(it) } ?: Duration.ofHours(24)
            val expiresSoon =
                TimeUtil.isWithinExpiryNotificationWindow(window, it.SelfNode.KeyExpiry)
            showExpiry.set(expiresSoon)
          }
        }
      }
    }

    viewModelScope.launch {
      App.get().healthNotifier?.currentIcon?.collect { icon -> healthIcon.set(icon) }
    }
  }

  fun showVPNPermissionLauncherIfUnauthorized() {
    val vpnIntent = VpnService.prepare(App.get())
    if (vpnIntent != null) {
      vpnPermissionLauncher?.launch(vpnIntent)
    } else {
      vpnViewModel.setVpnPrepared(true)
      startVPN()
    }
  }

  fun toggleVpn(desiredState: Boolean) {
    if (isToggleInProgress.value) {
      // Prevent toggling while a previous toggle is in progress
      return
    }

    viewModelScope.launch {
      isToggleInProgress.value = true
      try {
        val currentState = Notifier.state.value
        val isPrepared = vpnViewModel.vpnPrepared.value

        if (desiredState) {
          // User wants to turn ON the VPN
          when {
            !isPrepared -> showVPNPermissionLauncherIfUnauthorized()
            currentState != Ipn.State.Running -> startVPN()
          }
        } else {
          // User wants to turn OFF the VPN
          if (currentState == Ipn.State.Running) {
            stopVPN()
          }
        }
      } finally {
        isToggleInProgress.value = false
      }
    }
  }

  fun searchPeers(searchTerm: String) {
    this.searchTerm.set(searchTerm)
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
