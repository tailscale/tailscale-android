// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.dataSource

import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.IpnState
import kotlinx.coroutines.CoroutineScope

interface TailnetLockSetupDataSource {
  fun loadStatus(
      scope: CoroutineScope,
      callback: (Result<IpnState.NetworkLockStatus>) -> Unit,
  )
}

class TailnetLockSetupDataSourceImpl : TailnetLockSetupDataSource {
  override fun loadStatus(
      scope: CoroutineScope,
      callback: (Result<IpnState.NetworkLockStatus>) -> Unit,
  ) {
    Client(scope).tailnetLockStatus(callback)
  }
}
