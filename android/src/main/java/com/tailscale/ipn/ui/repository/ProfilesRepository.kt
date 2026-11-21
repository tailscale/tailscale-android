// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.repository

import com.tailscale.ipn.ui.dataSource.ProfilesDataSource
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.UserID
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfilesRepository(
    private val dataSource: ProfilesDataSource,
    private val scope: CoroutineScope,
) {
  private val TAG = "ProfilesRepository"

  private val _currentProfile = MutableStateFlow<IpnLocal.LoginProfile?>(null)
  val currentProfile: StateFlow<IpnLocal.LoginProfile?> = _currentProfile.asStateFlow()

  private val _profiles = MutableStateFlow<List<IpnLocal.LoginProfile>?>(null)
  val profiles: StateFlow<List<IpnLocal.LoginProfile>?> = _profiles.asStateFlow()

  private val refreshLock = Any()
  private var refreshGeneration = 0L

  init {
    scope.launch { dataSource.state.collect { refresh() } }

    scope.launch {
      var previousUserId: UserID? = null

      dataSource.netmap.collect { netmap ->
        val userId = netmap?.SelfNode?.User
        if (userId != previousUserId) {
          previousUserId = userId
          refresh()
        }
      }
    }
  }

  fun refresh() {
    val generation = synchronized(refreshLock) { ++refreshGeneration }

    dataSource.loadProfiles(
        scope = scope,
        onProfiles = { result ->
          result
              .onSuccess { profiles ->
                synchronized(refreshLock) {
                  if (generation == refreshGeneration) {
                    _profiles.value = profiles
                  }
                }
              }
              .onFailure { TSLog.e(TAG, "Error loading profiles: ${it.message}") }
        },
        onCurrentProfile = { result ->
          result
              .onSuccess { profile ->
                synchronized(refreshLock) {
                  if (generation == refreshGeneration) {
                    _currentProfile.value = if (profile.isEmpty()) null else profile
                  }
                }
              }
              .onFailure {
                TSLog.e(
                    TAG,
                    "Error loading current profile: ${it.message}",
                )
              }
        },
    )
  }
}
