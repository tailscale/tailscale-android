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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FavoritesManager
constructor(
    ipnStateFlow: StateFlow<Ipn.State>,
    netmapFlow: StateFlow<Netmap.NetworkMap?>,
    private val scope: CoroutineScope,
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val writeDebounce: Duration = 350.milliseconds,
) {
  private val TAG = "FavoritesManager"

  private val _favorites = MutableStateFlow<Favorites?>(null)
  val favorites: StateFlow<Favorites?> = _favorites

  private val _writing = MutableStateFlow(false)
  val writing: StateFlow<Boolean> = _writing

  private val userFlow = netmapFlow.mapNotNull { it?.User() }

  private val client = Client(scope)

  private var currentUser: UserID? = null
  private var pendingWrite: Job? = null
  private var revert: Favorites? = null

  init {
    scope.launch {
      userFlow.distinctUntilChanged().collect { user ->
        withContext(dispatcher) {
          currentUser = user
          pendingWrite?.cancel()
          revert = null
          _writing.value = false
          _favorites.value = null
        }
      }
    }

    scope.launch {
      combine(ipnStateFlow, userFlow) { state, user -> user.takeIf { state == Ipn.State.Running } }
          .filterNotNull()
          .distinctUntilChanged()
          .collect { load() }
    }
  }

  private fun load() {
    scope.launch(dispatcher) {
      val user = currentUser
      client.getFavorites { result ->
        scope.launch(dispatcher) {
          if (currentUser != user) return@launch
          result
              .onSuccess { _favorites.value = it }
              .onFailure { TSLog.e(TAG, "Error loading favorites: ${it.message}") }
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
        result
            .onSuccess { _favorites.value = it }
            .onFailure {
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
