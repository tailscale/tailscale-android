// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.dataSource

import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.IpnState
import com.tailscale.ipn.ui.model.Tailcfg
import kotlinx.coroutines.CoroutineScope

interface PingDataSource {
  fun ping(
      scope: CoroutineScope,
      peer: Tailcfg.Node,
      callback: (Result<IpnState.PingResult>) -> Unit,
  )

  fun status(
      scope: CoroutineScope,
      callback: (Result<IpnState.Status>) -> Unit,
  )
}

class PingDataSourceImpl : PingDataSource {
  override fun ping(
      scope: CoroutineScope,
      peer: Tailcfg.Node,
      callback: (Result<IpnState.PingResult>) -> Unit,
  ) {
    Client(scope).ping(peer, callback)
  }

  override fun status(
      scope: CoroutineScope,
      callback: (Result<IpnState.Status>) -> Unit,
  ) {
    Client(scope).status(callback)
  }
}
