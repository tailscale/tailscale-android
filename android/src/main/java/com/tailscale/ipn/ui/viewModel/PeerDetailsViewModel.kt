// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.viewModel

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.service.IpnModel
import com.tailscale.ipn.ui.util.DisplayAddress
import com.tailscale.ipn.ui.util.TimeUtil

data class PeerSettingInfo(val title:String, val value: String)

class PeerDetailsViewModel(val model: IpnModel, val nodeId: StableNodeID): ViewModel() {

    var addresses: List<DisplayAddress>  = emptyList()
    var info: List<PeerSettingInfo> = emptyList()

    val nodeName: String
    val connectedStr: String
    val connectedColor: Color

    init {
        val peer = model.netmap.value?.getPeer(nodeId)
        peer?.Addresses?.let {
            addresses = it.map { addr ->
                DisplayAddress(addr)}
        }

        peer?.let { p ->
            info = listOf(
                    PeerSettingInfo("OS", p.Hostinfo?.OS ?: ""),
                    PeerSettingInfo("Key Expiry", TimeUtil().keyExpiryFromGoTime(p.KeyExpiry))
            )}


        nodeName = peer?.ComputedName ?: ""
        connectedStr = if(peer?.Online == true) "Connected" else "Not Connected"
        connectedColor = if(peer?.Online == true) Color.Green else Color.Gray
    }
}