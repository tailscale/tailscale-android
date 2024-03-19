// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.content.Intent
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.IPNReceiver
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Ipn.State
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.PeerCategorizer
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel : IpnViewModel() {

  // The user readable state of the system
  val stateRes: StateFlow<Int> = MutableStateFlow(State.NoState.userStringRes())

  // The expected state of the VPN toggle
  val vpnToggleState: StateFlow<Boolean> = MutableStateFlow(false)

  // The list of peers
  val peers: StateFlow<List<PeerSet>> = MutableStateFlow(emptyList<PeerSet>())

  // The current state of the IPN for determining view visibility
  val ipnState = Notifier.state

  val prefs = Notifier.prefs
  val netmap = Notifier.netmap

  // The active search term for filtering peers
  val searchTerm: StateFlow<String> = MutableStateFlow("")

  // The peerID of the local node
  val selfPeerId = Notifier.netmap.value?.SelfNode?.StableID ?: ""

  private val peerCategorizer = PeerCategorizer(viewModelScope)

  init {
    viewModelScope.launch {
      Notifier.state.collect { state ->
        stateRes.set(state.userStringRes())
        vpnToggleState.set((state == State.Running || state == State.Starting))
      }
    }

    viewModelScope.launch {
      Notifier.netmap.collect { netmap ->
        peers.set(peerCategorizer.groupedAndFilteredPeers(searchTerm.value))
      }
    }
  }

  fun searchPeers(searchTerm: String) {
    this.searchTerm.set(searchTerm)
    viewModelScope.launch { peers.set(peerCategorizer.groupedAndFilteredPeers(searchTerm)) }
  }

  val userName: String
    get() {
      return loggedInUser.value?.Name ?: ""
    }

  fun toggleVpn() {
    when (Notifier.state.value) {
      State.Running -> stopVPN()
      else -> startVPN()
    }
  }

  private fun startVPN() {
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
}

private fun State?.userStringRes(): Int {
  return when (this) {
    State.NoState -> R.string.waiting
    State.InUseOtherUser -> R.string.placeholder
    State.NeedsLogin -> R.string.please_login
    State.NeedsMachineAuth -> R.string.placeholder
    State.Stopped -> R.string.stopped
    State.Starting -> R.string.starting
    State.Running -> R.string.connected
    else -> R.string.placeholder
  }
}
