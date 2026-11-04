// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.dataSource

import com.tailscale.ipn.App
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnState
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface SettingsDataSource {
  val netmap: StateFlow<Netmap.NetworkMap?>
  val prefs: StateFlow<Ipn.Prefs?>

  fun tailnetLockStatus(
      scope: CoroutineScope,
      callback: (Result<IpnState.NetworkLockStatus>) -> Unit,
  )

  fun isClientLoggingEnabled(): Boolean

  fun setClientLoggingEnabled(enabled: Boolean)
}

class SettingsDataSourceImpl : SettingsDataSource {
  override val netmap = Notifier.netmap
  override val prefs = Notifier.prefs

  override fun tailnetLockStatus(
      scope: CoroutineScope,
      callback: (Result<IpnState.NetworkLockStatus>) -> Unit,
  ) {
    Client(scope).tailnetLockStatus(callback)
  }

  override fun isClientLoggingEnabled() = App.get().isClientLoggingEnabled

  override fun setClientLoggingEnabled(enabled: Boolean) {
    App.get().updateIsClientLoggingEnabled(enabled)
  }
}
