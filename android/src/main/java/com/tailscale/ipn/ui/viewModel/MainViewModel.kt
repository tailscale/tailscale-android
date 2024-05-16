// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Ipn.State
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.PeerCategorizer
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.TimeUtil
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Duration

class MainViewModel : IpnViewModel() {

  // The user readable state of the system
  val stateRes: StateFlow<Int> = MutableStateFlow(State.NoState.userStringRes(null))

  // The expected state of the VPN toggle
  private val _vpnToggleState = MutableStateFlow(false)
  val vpnToggleState: StateFlow<Boolean> = _vpnToggleState  

  // The list of peers
  val peers: StateFlow<List<PeerSet>> = MutableStateFlow(emptyList<PeerSet>())

  // The current state of the IPN for determining view visibility
  val ipnState = Notifier.state

  val prefs = Notifier.prefs
  val netmap = Notifier.netmap

  // The active search term for filtering peers
  val searchTerm: StateFlow<String> = MutableStateFlow("")

  // True if we should render the key expiry bannder
  val showExpiry: StateFlow<Boolean> = MutableStateFlow(false)

  private val peerCategorizer = PeerCategorizer()

  init {

    viewModelScope.launch {
      var previousState: State? = null
      Notifier.state.collect { currentState ->
          val userString = currentState.userStringRes(previousState)
          stateRes.set(userString)
          _vpnToggleState.value = when {
              currentState == State.Running || currentState == State.Starting -> true
              previousState == State.NoState && currentState == State.Starting -> true
              else -> false
          }
          previousState = currentState
      }
  }

    viewModelScope.launch {
      Notifier.netmap.collect { it ->
        it?.let { netmap ->
          peerCategorizer.regenerateGroupedPeers(netmap)
          peers.set(peerCategorizer.groupedAndFilteredPeers(searchTerm.value))

          if (netmap.SelfNode.keyDoesNotExpire) {
            showExpiry.set(false)
            return@let
          } else {
            val expiryNotificationWindowMDM = MDMSettings.keyExpirationNotice.flow.value
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
      searchTerm.collect { term -> peers.set(peerCategorizer.groupedAndFilteredPeers(term)) }
    }

    viewModelScope.launch {
      Notifier.prefs.collect { prefs -> Log.d(TAG, "Main VM - prefs = ${prefs}") }
    }
  }

  fun searchPeers(searchTerm: String) {
    this.searchTerm.set(searchTerm)
  }
}

private fun State?.userStringRes(previousState: State?): Int {
  val resId = when {
      previousState == State.NoState && this == State.Starting -> R.string.starting
      this == State.NoState -> R.string.placeholder
      this == State.InUseOtherUser -> R.string.placeholder
      this == State.NeedsLogin -> R.string.please_login
      this == State.NeedsMachineAuth -> R.string.needs_machine_auth
      this == State.Stopped -> R.string.stopped
      this == State.Starting -> R.string.starting
      this == State.Running -> R.string.connected
      else -> R.string.placeholder
  }
  return resId
}
