// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.model.Ipn.State
import com.tailscale.ipn.ui.service.IpnActions
import com.tailscale.ipn.ui.service.IpnModel
import com.tailscale.ipn.ui.util.PeerCategorizer
import com.tailscale.ipn.ui.util.PeerSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(val model: IpnModel, val actions: IpnActions) : ViewModel() {

    private val _stateStr = MutableStateFlow<String>("")
    private val _tailnetName = MutableStateFlow<String>("")
    private val _vpnToggleState = MutableStateFlow<Boolean>(false)
    private val _peers = MutableStateFlow<List<PeerSet>>(emptyList<PeerSet>())

    // The user readable state of the system
    val stateStr = _stateStr.asStateFlow()

    // The current state of the IPN for determining view visibility
    val ipnState = model.state

    // The name of the tailnet
    val tailnetName = _tailnetName.asStateFlow()

    // The expected state of the VPN toggle
    val vpnToggleState = _vpnToggleState.asStateFlow()

    // The list of peers
    val peers = _peers.asStateFlow()

    // The logged in user
    val loggedInUser = model.loggedInUser

    // The active search term for filtering peers
    val searchTerm = MutableStateFlow<String>("")


    init {
        viewModelScope.launch {
            model.state.collect { state ->
                _stateStr.value = state.userString()
                _vpnToggleState.value = (state == State.Running || state == State.Starting)
            }
        }

        viewModelScope.launch {
            model.netmap.collect { netmap ->
                _tailnetName.value = netmap?.Domain ?: ""
                _peers.value = PeerCategorizer(model).groupedAndFilteredPeers(searchTerm.value)
            }
        }
    }

    fun searchPeers(searchTerm: String) {
        this.searchTerm.value = searchTerm
        viewModelScope.launch {
            _peers.value = PeerCategorizer(model).groupedAndFilteredPeers(searchTerm)
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

private fun State?.userString(): String {
    return when (this) {
        State.NoState -> "Waiting..."
        State.InUseOtherUser -> "--"
        State.NeedsLogin -> "Please Login"
        State.NeedsMachineAuth -> "--"
        State.Stopped -> "Stopped"
        State.Starting -> "Starting"
        State.Running -> "Connected"
        else -> "--"
    }
}
