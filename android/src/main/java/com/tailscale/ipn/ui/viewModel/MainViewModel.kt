// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause


package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Ipn.State
import com.tailscale.ipn.ui.service.IpnActions
import com.tailscale.ipn.ui.service.IpnModel
import com.tailscale.ipn.ui.service.set
import com.tailscale.ipn.ui.util.PeerCategorizer
import com.tailscale.ipn.ui.util.PeerSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(val model: IpnModel, val actions: IpnActions) : ViewModel() {

    // The user readable state of the system
    val stateRes: StateFlow<Int> = MutableStateFlow(State.NoState.userStringRes())

    // The expected state of the VPN toggle
    val vpnToggleState: StateFlow<Boolean> = MutableStateFlow(false)

    // The list of peers
    val peers: StateFlow<List<PeerSet>> = MutableStateFlow(emptyList<PeerSet>())

    // The current state of the IPN for determining view visibility
    val ipnState = model.state

    // The logged in user
    val loggedInUser = model.loggedInUser

    // The active search term for filtering peers
    val searchTerm: StateFlow<String> = MutableStateFlow("")

    val selfPeerId = model.netmap.value?.SelfNode?.StableID ?: ""

    init {
        viewModelScope.launch {
            model.state.collect { state ->
                stateRes.set(state.userStringRes())
                vpnToggleState.set((state == State.Running || state == State.Starting))
            }
        }

        viewModelScope.launch {
            model.netmap.collect { netmap ->
                peers.set(PeerCategorizer(model).groupedAndFilteredPeers(searchTerm.value))
            }
        }
    }

    fun searchPeers(searchTerm: String) {
        this.searchTerm.set(searchTerm)
        viewModelScope.launch {
            peers.set(PeerCategorizer(model).groupedAndFilteredPeers(searchTerm))
        }
    }

    val userName: String
        get() {
            return loggedInUser.value?.Name ?: ""
        }

    fun toggleVpn() {
        when (model.state.value) {
            State.Running -> actions.stopVPN()
            else -> actions.startVPN()
        }
    }

    fun login() {
        actions.login()
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
