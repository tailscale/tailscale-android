// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import com.tailscale.localapi.PintypeItem
import com.tailscale.localapi.PintypeSet
import com.tailscale.localapi.SetPinsRequest

typealias FavoriteItem = PintypeItem

typealias Favorites = PintypeSet

typealias FavoritesRequest = SetPinsRequest

val Favorites.deviceIds: List<StableNodeID>
  get() = devices.orEmpty().mapNotNull { it.ID }

fun Favorites.isFavoriteDevice(id: StableNodeID): Boolean = id in deviceIds

fun Favorites.withToggledDevice(id: StableNodeID): FavoritesRequest {
  val current = devices.orEmpty()
  val updated =
      if (isFavoriteDevice(id)) {
        current.filterNot { it.ID == id }
      } else {
        current + FavoriteItem(ID = id)
      }
  return FavoritesRequest(
      pins = copy(devices = updated),
      devicesSet = true,
  )
}
