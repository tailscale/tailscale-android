// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Ipn.State
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.PeerCategorizer
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.TimeUtil
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Duration

class MainViewModel : IpnViewModel() {

  // The user readable state of the system
  val stateRes: StateFlow<Int> = MutableStateFlow(userStringRes(State.NoState, State.NoState, true))

  // The expected state of the VPN toggle
  private val _vpnToggleState = MutableStateFlow(false)
  val vpnToggleState: StateFlow<Boolean> = _vpnToggleState

  // Whether or not the VPN has been prepared
  private val _vpnPrepared = MutableStateFlow(false)
  val vpnPrepared: StateFlow<Boolean> = _vpnPrepared
  private var vpnPermissionLauncher: ActivityResultLauncher<Intent>? = null

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
  
      combine(Notifier.state, vpnPrepared) { state, prepared -> state to prepared }
          .collect { (currentState, prepared) ->
              stateRes.set(userStringRes(currentState, previousState, prepared))
  
              val isOn = when {
                  currentState == State.Running || currentState == State.Starting -> true
                  previousState == State.NoState && currentState == State.Starting -> true
                  else -> false
              }
  
              _vpnToggleState.value = isOn
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

  fun showVPNPermissionLauncherIfUnauthorized() {
    val vpnIntent = VpnService.prepare(App.get())
    if (vpnIntent != null) {
      vpnPermissionLauncher?.launch(vpnIntent)
    }
  }

  fun toggleVpn() {
    val state = Notifier.state.value
    val isPrepared = vpnPrepared.value

    when {
        !isPrepared -> showVPNPermissionLauncherIfUnauthorized()
        state == Ipn.State.Running -> stopVPN()
        else -> startVPN()
    }
}

  fun searchPeers(searchTerm: String) {
    this.searchTerm.set(searchTerm)
  }

  fun setVpnPermissionLauncher(launcher: ActivityResultLauncher<Intent>) {
    vpnPermissionLauncher = launcher
  }

  fun setVpnPrepared(prepared: Boolean) {
    _vpnPrepared.value = prepared
  }
}

private fun userStringRes(currentState: State?, previousState: State?, vpnPrepared: Boolean): Int {
  return when {
      previousState == State.NoState && currentState == State.Starting -> R.string.starting
      currentState == State.NoState -> R.string.placeholder
      currentState == State.InUseOtherUser -> R.string.placeholder
      currentState == State.NeedsLogin -> if (vpnPrepared) R.string.please_login else R.string.connect_to_vpn
      currentState == State.NeedsMachineAuth -> R.string.needs_machine_auth
      currentState == State.Stopped -> R.string.stopped
      currentState == State.Starting -> R.string.starting
      currentState == State.Running -> R.string.connected
      else -> R.string.placeholder
  }
}