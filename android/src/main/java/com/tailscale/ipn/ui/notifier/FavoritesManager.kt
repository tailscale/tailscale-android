// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Favorites
import com.tailscale.ipn.ui.model.FavoritesRequest
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.model.UserID
import com.tailscale.ipn.util.TSLog
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FavoritesManager(
    ipnStateFlow: StateFlow<Ipn.State>,
    netmapFlow: StateFlow<Netmap.NetworkMap?>,
    private val scope: CoroutineScope,
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val writeDebounce: Duration = 350.milliseconds,
    private val retryDelay: Duration = 2.seconds,
) {
  private val TAG = "FavoritesManager"

  private val _favorites = MutableStateFlow<Favorites?>(null)
  val favorites: StateFlow<Favorites?> = _favorites

  private val _writing = MutableStateFlow(false)
  val writing: StateFlow<Boolean> = _writing

  private val userFlow = netmapFlow.mapNotNull { it?.User() }

  private val client = Client(scope)

  private var currentUser: UserID? = null
  private var loadedForUser: UserID? = null
  private var pendingWrite: Job? = null
  private var revert: Favorites? = null

  init {
    scope.launch {
      combine(ipnStateFlow, userFlow) { state, user -> state to user }
          .distinctUntilChanged()
          .collect { (state, user) ->
            withContext(dispatcher) {
              if (user != currentUser) {
                currentUser = user
                loadedForUser = null
                pendingWrite?.cancel()
                revert = null
                _writing.value = false
                _favorites.value = null
              }
              if (state == Ipn.State.Running && loadedForUser != user) {
                loadedForUser = user
                load(user)
              }
            }
          }
    }
  }

  private fun load(user: UserID, isRetry: Boolean = false) {
    client.getFavorites { result ->
      scope.launch(dispatcher) {
        if (currentUser != user) return@launch
        result
            .onSuccess { _favorites.value = it }
            .onFailure {
              TSLog.e(TAG, "Error loading favorites: ${it.message}")
              loadedForUser = null
              if (isRetry) return@onFailure
              scope.launch(dispatcher) {
                delay(retryDelay)
                if (currentUser == user && loadedForUser == null) {
                  loadedForUser = user
                  load(user, isRetry = true)
                }
              }
            }
      }
    }
  }

  private fun send(request: FavoritesRequest) {
    val snapshot = revert
    val user = currentUser
    revert = null
    _writing.value = true
    client.setFavorites(request) { result ->
      scope.launch(dispatcher) {
        if (currentUser != user) return@launch
        _writing.value = false
        if (revert != null) return@launch // newer burst opened while inflight
        result.onFailure {
          TSLog.e(TAG, "Error writing favorites: ${it.message}")
          _favorites.value = snapshot
        }
      }
    }
  }

  fun toggleDevice(id: StableNodeID) {
    scope.launch(dispatcher) {
      val current = _favorites.value ?: return@launch
      if (revert == null) revert = current
      pendingWrite?.cancel()

      val request = current.withToggledDevice(id)
      _favorites.value = request.pins

      pendingWrite =
          scope.launch(dispatcher) {
            delay(writeDebounce)
            send(request)
          }
    }
  }
}
