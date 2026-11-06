// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.dataSource

import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface SubnetRoutingDataSource {
  val prefs: StateFlow<Ipn.Prefs?>

  fun editPrefs(
      scope: CoroutineScope,
      prefsOut: Ipn.MaskedPrefs,
      callback: (Result<Ipn.Prefs>) -> Unit,
  )

  fun reloadPrefs(
      scope: CoroutineScope,
      callback: (Result<Ipn.Prefs>) -> Unit,
  )
}

class AndroidSubnetRoutingDataSource : SubnetRoutingDataSource {
  override val prefs: StateFlow<Ipn.Prefs?> = Notifier.prefs

  override fun editPrefs(
      scope: CoroutineScope,
      prefsOut: Ipn.MaskedPrefs,
      callback: (Result<Ipn.Prefs>) -> Unit,
  ) {
    Client(scope).editPrefs(prefsOut, callback)
  }

  override fun reloadPrefs(
      scope: CoroutineScope,
      callback: (Result<Ipn.Prefs>) -> Unit,
  ) {
    Client(scope).prefs(callback)
  }
}
