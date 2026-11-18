// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.dataSource

import com.tailscale.ipn.mdm.AlwaysNeverUserDecides
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.notifier.SelfNodeUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface DNSSettingsDataSource {
  val prefs: StateFlow<Ipn.Prefs?>
  val dnsSettingsPolicy: StateFlow<SettingState<AlwaysNeverUserDecides>>
  val state: StateFlow<Ipn.State>
  val selfNodeUpdate: StateFlow<SelfNodeUpdate?>

  fun editPrefs(
      scope: CoroutineScope,
      prefs: Ipn.MaskedPrefs,
      callback: (Result<Ipn.Prefs>) -> Unit,
  )

  fun dnsConfig(
      scope: CoroutineScope,
      callback: (Result<Tailcfg.DNSConfig>) -> Unit,
  )
}

class DNSSettingsDataSourceImpl : DNSSettingsDataSource {
  override val prefs = Notifier.prefs
  override val dnsSettingsPolicy = MDMSettings.useTailscaleDNSSettings.flow
  override val state = Notifier.state
  override val selfNodeUpdate = Notifier.selfNodeUpdate

  override fun editPrefs(
      scope: CoroutineScope,
      prefs: Ipn.MaskedPrefs,
      callback: (Result<Ipn.Prefs>) -> Unit,
  ) {
    Client(scope).editPrefs(prefs, callback)
  }

  override fun dnsConfig(
      scope: CoroutineScope,
      callback: (Result<Tailcfg.DNSConfig>) -> Unit,
  ) {
    Client(scope).dnsConfig(callback)
  }
}
