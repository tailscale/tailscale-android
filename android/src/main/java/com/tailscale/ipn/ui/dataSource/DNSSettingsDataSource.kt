// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.dataSource

import com.tailscale.ipn.mdm.AlwaysNeverUserDecides
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface DNSSettingsDataSource {
  val prefs: StateFlow<Ipn.Prefs?>
  val netmap: StateFlow<Netmap.NetworkMap?>
  val dnsSettingsPolicy: StateFlow<SettingState<AlwaysNeverUserDecides>>

  fun editPrefs(
      scope: CoroutineScope,
      prefs: Ipn.MaskedPrefs,
      callback: (Result<Ipn.Prefs>) -> Unit,
  )
}

class AndroidDNSSettingsDataSource : DNSSettingsDataSource {
  override val prefs = Notifier.prefs
  override val netmap = Notifier.netmap
  override val dnsSettingsPolicy = MDMSettings.useTailscaleDNSSettings.flow

  override fun editPrefs(
      scope: CoroutineScope,
      prefs: Ipn.MaskedPrefs,
      callback: (Result<Ipn.Prefs>) -> Unit,
  ) {
    Client(scope).editPrefs(prefs, callback)
  }
}
