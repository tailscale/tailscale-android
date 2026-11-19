// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.dataSource

import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface ExitNodeStatusDataSource {
  val prefs: StateFlow<Ipn.Prefs?>
  val netmap: StateFlow<Netmap.NetworkMap?>

  fun forcedExitNodeId(): String?

  fun setUseExitNode(
      scope: CoroutineScope,
      enabled: Boolean,
      callback: (Result<Ipn.Prefs>) -> Unit,
  )

  fun setRunningExitNode(
      scope: CoroutineScope,
      currentPrefs: Ipn.Prefs,
      isOn: Boolean,
      callback: (Result<Ipn.Prefs>) -> Unit,
  )
}

class ExitNodeStatusDataSourceImpl(
    private val runExitNode: RunExitNodeDataSource,
) : ExitNodeStatusDataSource {
  override val prefs = Notifier.prefs
  override val netmap = Notifier.netmap

  override fun forcedExitNodeId(): String? = MDMSettings.exitNodeID.flow.value.value

  override fun setUseExitNode(
      scope: CoroutineScope,
      enabled: Boolean,
      callback: (Result<Ipn.Prefs>) -> Unit,
  ) {
    Client(scope).setUseExitNode(enabled, callback)
  }

  override fun setRunningExitNode(
      scope: CoroutineScope,
      currentPrefs: Ipn.Prefs,
      isOn: Boolean,
      callback: (Result<Ipn.Prefs>) -> Unit,
  ) {
    runExitNode.setRunningExitNode(
        scope = scope,
        currentPrefs = currentPrefs,
        isOn = isOn,
        callback = callback,
    )
  }
}
