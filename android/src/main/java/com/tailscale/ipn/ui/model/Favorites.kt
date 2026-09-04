package com.tailscale.ipn.ui.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FavoriteItem(
    @SerialName("ID") var id: String? = null,
    @SerialName("Name") var name: String? = null,
)

@Serializable
data class Favorites(
    @SerialName("Devices") val devices: List<FavoriteItem>? = null,
    @SerialName("ExitNodes") val exitNodes: List<FavoriteItem>? = null,
    @SerialName("Services") val services: List<FavoriteItem>? = null,
) {
  val deviceIds: List<String>
    get() = devices.orEmpty().mapNotNull { it.id }

  fun isFavoriteDevice(id: StableNodeID): Boolean {
    return deviceIds.contains(id)
  }

  fun withToggledDevice(id: StableNodeID): FavoritesRequest {
    val current = devices.orEmpty()
    val updated =
        if (isFavoriteDevice(id)) {
          current.filterNot { it.id == id }
        } else {
          current + FavoriteItem(id = id)
        }
    return FavoritesRequest(
        pins = copy(devices = updated),
        devicesSet = true,
    )
  }
}

@Serializable
data class FavoritesRequest(
    @SerialName("Pins") val pins: Favorites,
    @SerialName("DevicesSet") val devicesSet: Boolean? = null,
    @SerialName("ExitNodesSet") val exitNodesSet: Boolean? = null,
    @SerialName("ServicesSet") val servicesSet: Boolean? = null,
)
