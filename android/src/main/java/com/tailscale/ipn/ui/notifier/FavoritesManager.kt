// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Favorites
import com.tailscale.ipn.ui.model.FavoritesRequest
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.ProfileID
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.util.TSLog
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

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

  private val nodeFlow = netmapFlow.map { it?.SelfNode?.StableID }

  private val client = Client(scope)
  // newer request replaces undelivered one
  private val writes = Channel<FavoritesRequest>(Channel.CONFLATED)

  // All below guarded by dispatcher
  private var currentProfile: ProfileID? = null
  private var loadedForProfile: ProfileID? = null
  private var loadedForNode: StableNodeID? = null
  private var profileSeq = 0L
  private var pendingWrite: Job? = null
  private var revert: Favorites? = null

  init {
    scope.launch(dispatcher) {
      combine(ipnStateFlow, nodeFlow) { state, node -> state to node }
          .distinctUntilChanged()
          .collect { (state, node) ->
            val seq = ++profileSeq
            if (node != loadedForNode) {
              loadedForNode = node
              reset()
            }
            if (state == Ipn.State.Running) resolveProfile(seq)
          }
    }

    scope.launch(dispatcher) { for (request in writes) write(request) }
  }

  private fun resolveProfile(seq: Long) {
    client.currentProfile { result ->
      scope.launch(dispatcher) {
        if (seq != profileSeq) return@launch
        val profile =
            result
                .onFailure { TSLog.e(TAG, "Error loading current profile: ${it.message}") }
                .getOrNull()
                ?.ID
                ?.takeIf { it.isNotEmpty() }
        if (profile == null) return@launch // dont know what profile we are on so leave state alone
        if (profile != currentProfile) {
          reset()
          currentProfile = profile
        }
        if (loadedForProfile != profile) {
          loadedForProfile = profile
          load(profile)
        }
      }
    }
  }

  private fun reset() {
    currentProfile = null
    loadedForProfile = null
    pendingWrite?.cancel()
    writes.tryReceive() // drop write queued for previous profile
    revert = null
    _writing.value = false
    _favorites.value = null
  }

  private fun load(profile: ProfileID, isRetry: Boolean = false) {
    client.getFavorites { result ->
      scope.launch(dispatcher) {
        if (currentProfile != profile) return@launch
        result
            .onSuccess { _favorites.value = it }
            .onFailure {
              TSLog.e(TAG, "Error loading favorites: ${it.message}")
              loadedForProfile = null
              if (isRetry) return@onFailure
              scope.launch(dispatcher) {
                delay(retryDelay)
                if (currentProfile == profile && loadedForProfile == null) {
                  loadedForProfile = profile
                  load(profile, isRetry = true)
                }
              }
            }
      }
    }
  }

  private suspend fun write(request: FavoritesRequest) {
    val snapshot = revert
    val profile = currentProfile
    revert = null
    _writing.value = true

    val result = suspendCancellableCoroutine { cont ->
      client.setFavorites(request) { cont.resume(it) }
    }

    if (currentProfile != profile) return
    _writing.value = false
    if (revert != null) return // newer burst opened while in flight
    result.onFailure {
      TSLog.e(TAG, "Error writing favorites: ${it.message}")
      _favorites.value = snapshot
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
            writes.trySend(request)
          }
    }
  }
}
