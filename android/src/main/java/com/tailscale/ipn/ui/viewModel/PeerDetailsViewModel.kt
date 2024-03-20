// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.theme.ts_color_light_green
import com.tailscale.ipn.ui.util.ComposableStringFormatter
import com.tailscale.ipn.ui.util.DisplayAddress
import com.tailscale.ipn.ui.util.TimeUtil
import java.io.File

data class PeerSettingInfo(val titleRes: Int, val value: ComposableStringFormatter)

class PeerDetailsViewModelFactory(private val nodeId: StableNodeID, private val filesDir: File) :
    ViewModelProvider.Factory {
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return PeerDetailsViewModel(nodeId, filesDir) as T
  }
}

class PeerDetailsViewModel(val nodeId: StableNodeID, val filesDir: File) : IpnViewModel() {

  var addresses: List<DisplayAddress> = emptyList()
  var info: List<PeerSettingInfo> = emptyList()

  val nodeName: String
  val connectedStrRes: Int
  val connectedColor: Color

  init {
    val peer = Notifier.netmap.value?.getPeer(nodeId)
    peer?.Addresses?.let { addresses = it.map { addr -> DisplayAddress(addr) } }

    peer?.Name?.let { addresses = listOf(DisplayAddress(it)) + addresses }

    peer?.let { p ->
      info =
          listOf(
              PeerSettingInfo(R.string.os, ComposableStringFormatter(p.Hostinfo.OS ?: "")),
              PeerSettingInfo(R.string.key_expiry, TimeUtil().keyExpiryFromGoTime(p.KeyExpiry)))
    }

    nodeName = peer?.ComputedName ?: ""
    connectedStrRes = if (peer?.Online == true) R.string.connected else R.string.not_connected
    connectedColor = if (peer?.Online == true) ts_color_light_green else Color.Gray
  }
}
