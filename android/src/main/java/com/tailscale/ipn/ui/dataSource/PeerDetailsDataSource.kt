// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.dataSource

import com.tailscale.ipn.App
import com.tailscale.ipn.ui.model.Favorites
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.notifier.FavoritesManager
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.flow.StateFlow

interface PeerDetailsDataSource {
  val netmap: StateFlow<Netmap.NetworkMap?>
  val favorites: StateFlow<Favorites?>
  val isWritingFavorites: StateFlow<Boolean>

  fun toggleFavorite(id: StableNodeID)
}

class PeerDetailsDataSourceImpl : PeerDetailsDataSource {
  private val favoritesManager: FavoritesManager = App.get().favoritesManager

  override val netmap: StateFlow<Netmap.NetworkMap?> = Notifier.netmap
  override val favorites: StateFlow<Favorites?> = favoritesManager.favorites
  override val isWritingFavorites: StateFlow<Boolean> = favoritesManager.writing

  override fun toggleFavorite(id: StableNodeID) {
    favoritesManager.toggleDevice(id)
  }
}
