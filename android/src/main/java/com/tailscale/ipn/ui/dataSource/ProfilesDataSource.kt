// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.dataSource

import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface ProfilesDataSource {
  val state: StateFlow<Ipn.State>
  val netmap: StateFlow<Netmap.NetworkMap?>

  fun loadProfiles(
      scope: CoroutineScope,
      onProfiles: (Result<List<IpnLocal.LoginProfile>>) -> Unit,
      onCurrentProfile: (Result<IpnLocal.LoginProfile>) -> Unit,
  )
}

class ProfilesDataSourceImpl : ProfilesDataSource {
  override val state = Notifier.state
  override val netmap = Notifier.netmap

  override fun loadProfiles(
      scope: CoroutineScope,
      onProfiles: (Result<List<IpnLocal.LoginProfile>>) -> Unit,
      onCurrentProfile: (Result<IpnLocal.LoginProfile>) -> Unit,
  ) {
    val client = Client(scope)
    client.profiles(onProfiles)
    client.currentProfile(onCurrentProfile)
  }
}
