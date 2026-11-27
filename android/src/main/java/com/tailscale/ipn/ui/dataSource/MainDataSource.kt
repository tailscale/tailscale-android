// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.dataSource

import com.tailscale.ipn.App
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.ui.model.Favorites
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface MainDataSource {
  val netmap: StateFlow<Netmap.NetworkMap?>
  val favorites: StateFlow<Favorites?>
  val healthIcon: StateFlow<Int?>
  val prefs: StateFlow<Ipn.Prefs?>
  val managedByOrganization: StateFlow<SettingState<String?>>

  fun toggleFavorite(id: StableNodeID)

  fun skipPromptsForAuthKeyLogin(): Boolean

  fun keyExpirationNotice(): String?
}

class MainDataSourceImpl : MainDataSource {
  private val favoritesManager = App.get().favoritesManager

  override val netmap = Notifier.netmap

  override val favorites = favoritesManager.favorites

  override val healthIcon: StateFlow<Int?> =
      App.get().healthNotifier?.currentIcon ?: MutableStateFlow(null)

  override val prefs = Notifier.prefs

  override val managedByOrganization = MDMSettings.managedByOrganizationName.flow

  override fun skipPromptsForAuthKeyLogin(): Boolean =
      !MDMSettings.authKey.flow.value.value.isNullOrEmpty()

  override fun keyExpirationNotice(): String? = MDMSettings.keyExpirationNotice.flow.value.value

  override fun toggleFavorite(id: StableNodeID) {
    favoritesManager.toggleDevice(id)
  }
}
