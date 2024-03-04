package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.model.Ipn.State
import com.tailscale.ipn.ui.model.Tailcfg.Node
import com.tailscale.ipn.ui.service.IpnManager
import com.tailscale.ipn.ui.service.IpnModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(val model: IpnModel) : ViewModel() {

    private val _stateStr = MutableStateFlow<String>("")
    private val _tailnetName = MutableStateFlow<String>("")
    private val _vpnToggleState = MutableStateFlow<Boolean>(false)
    private val _peers = MutableStateFlow<List<Node>>(emptyList<Node>())

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
                _peers.value = netmap?.Peers ?: emptyList<Node>()
            }
        }
    }

    val userName: String
        get() {
            return loggedInUser.value?.Name ?: ""
        }

    fun toggleVpn() {
        when (model.state.value) {
            State.Running -> IpnManager.getInstance().stopVPN()
            else -> IpnManager.getInstance().startVPN()
        }
    }

    fun login() {
        IpnManager.getInstance().login()
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
