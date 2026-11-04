// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.dataSource

import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface UserSwitcherDataSource {
  val state: StateFlow<Ipn.State>
  val netmap: StateFlow<Netmap.NetworkMap?>

  fun loadProfiles(
      scope: CoroutineScope,
      onProfiles: (Result<List<IpnLocal.LoginProfile>>) -> Unit,
      onCurrentProfile: (Result<IpnLocal.LoginProfile>) -> Unit,
  )

  fun logout(scope: CoroutineScope, completionHandler: (Result<String>) -> Unit)

  fun switchProfile(
      scope: CoroutineScope,
      profile: IpnLocal.LoginProfile,
      completionHandler: (Result<String>) -> Unit,
  )

  fun addProfile(scope: CoroutineScope, completionHandler: (Result<String>) -> Unit)

  fun deleteProfile(
      scope: CoroutineScope,
      profile: IpnLocal.LoginProfile,
      completionHandler: (Result<String>) -> Unit,
  )

  fun startVPN()
}

class UserSwitcherDataSourceImpl : UserSwitcherDataSource {
  private val tag = "AndroidUserSwitcherDataSource"

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

  override fun logout(scope: CoroutineScope, completionHandler: (Result<String>) -> Unit) {
    Client(scope).logout { result ->
      result
          .onSuccess { TSLog.d(tag, "Logout started: $it") }
          .onFailure { TSLog.e(tag, "Error starting logout: ${it.message}") }
      completionHandler(result)
    }
  }

  override fun switchProfile(
      scope: CoroutineScope,
      profile: IpnLocal.LoginProfile,
      completionHandler: (Result<String>) -> Unit,
  ) {
    Client(scope).editPrefs(Ipn.MaskedPrefs().apply { WantRunning = false }) { result ->
      result
          .onSuccess {
            Client(scope).switchProfile(profile) {
              startVPN()
              completionHandler(it)
            }
          }
          .onFailure {
            // Preserve the existing behavior: log this failure without invoking the callback.
            TSLog.e(tag, "Error setting wantRunning to false: ${it.message}")
          }
    }
  }

  override fun addProfile(scope: CoroutineScope, completionHandler: (Result<String>) -> Unit) {
    Client(scope).addProfile(completionHandler)
  }

  override fun deleteProfile(
      scope: CoroutineScope,
      profile: IpnLocal.LoginProfile,
      completionHandler: (Result<String>) -> Unit,
  ) {
    Client(scope).deleteProfile(profile, completionHandler)
  }

  override fun startVPN() {
    UninitializedApp.get().startVPN()
  }
}
