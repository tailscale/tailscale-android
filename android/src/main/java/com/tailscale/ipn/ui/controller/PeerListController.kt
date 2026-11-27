// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.controller

import com.tailscale.ipn.ui.model.Favorites
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.util.PeerCategorizer
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.withPinnedSection
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
class PeerListController(
    private val netmap: StateFlow<Netmap.NetworkMap?>,
    private val favorites: StateFlow<Favorites?>,
    private val scope: CoroutineScope,
) {
  private val peerCategorizer = PeerCategorizer()
  private val categorizerDispatcher = Dispatchers.Default.limitedParallelism(1)

  private var searchJob: Job? = null

  private val _searchTerm = MutableStateFlow("")
  val searchTerm: StateFlow<String> = _searchTerm.asStateFlow()

  private val _groupedPeers = MutableStateFlow<List<PeerSet>>(emptyList())

  private val _peers = MutableStateFlow<List<PeerSet>>(emptyList())
  val peers: StateFlow<List<PeerSet>> = _peers.asStateFlow()

  private val _searchViewPeers = MutableStateFlow<List<PeerSet>>(emptyList())
  val searchViewPeers: StateFlow<List<PeerSet>> = _searchViewPeers.asStateFlow()

  init {
    scope.launch {
      _searchTerm.debounce(250L.milliseconds).collect { term ->
        searchJob?.cancel()
        searchJob =
            this@launch.launch(categorizerDispatcher) {
              _searchViewPeers.value = peerCategorizer.groupedAndFilteredPeers(term)
            }
      }
    }

    scope.launch {
      netmap.filterNotNull().collectLatest { netmap ->
        searchJob?.cancel()
        withContext(categorizerDispatcher) {
          peerCategorizer.regenerateGroupedPeers(netmap)
          val filteredPeers = peerCategorizer.groupedAndFilteredPeers(searchTerm.value)

          _groupedPeers.value = peerCategorizer.peerSets
          _searchViewPeers.value = filteredPeers
        }
      }
    }

    scope.launch {
      combine(_groupedPeers, favorites) { sets, favs -> sets to favs }
          .collectLatest { (sets, favs) ->
            withContext(categorizerDispatcher) {
              _peers.value = sets.withPinnedSection(favs?.deviceIds.orEmpty())
            }
          }
    }
  }

  fun updateSearchTerm(term: String) {
    _searchTerm.value = term
  }
}
