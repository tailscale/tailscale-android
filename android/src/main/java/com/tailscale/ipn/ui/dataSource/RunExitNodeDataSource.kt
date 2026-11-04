// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.dataSource

import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface RunExitNodeDataSource {
  val prefs: StateFlow<Ipn.Prefs?>

  fun setRunningExitNode(
      scope: CoroutineScope,
      currentPrefs: Ipn.Prefs,
      isOn: Boolean,
      callback: (Result<Ipn.Prefs>) -> Unit,
  )
}

class RunExitNodeDataSourceImpl : RunExitNodeDataSource {
  override val prefs = Notifier.prefs

  override fun setRunningExitNode(
      scope: CoroutineScope,
      currentPrefs: Ipn.Prefs,
      isOn: Boolean,
      callback: (Result<Ipn.Prefs>) -> Unit,
  ) {
    val prefsOut = if (isOn) setZeroRoutes(currentPrefs) else removeAllZeroRoutes(currentPrefs)

    Client(scope).editPrefs(prefsOut, callback)
  }

  private fun setZeroRoutes(prefs: Ipn.Prefs): Ipn.MaskedPrefs {
    val routes = removeAllZeroRoutes(prefs).AdvertiseRoutes.orEmpty().toMutableList()
    routes.add("0.0.0.0/0")
    routes.add("::/0")

    return Ipn.MaskedPrefs().apply { AdvertiseRoutes = routes }
  }

  private fun removeAllZeroRoutes(prefs: Ipn.Prefs): Ipn.MaskedPrefs {
    val routes = prefs.AdvertiseRoutes.orEmpty().filterNot { it == "0.0.0.0/0" || it == "::/0" }

    return Ipn.MaskedPrefs().apply { AdvertiseRoutes = routes }
  }
}
