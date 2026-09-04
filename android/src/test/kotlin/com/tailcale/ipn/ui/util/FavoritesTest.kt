// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// DESTINATION: android/src/test/kotlin/com/tailcale/ipn/ui/model/FavoritesTest.kt
//
// Run with:  cd android && ./gradlew test --tests '*FavoritesTest*'

package com.tailcale.ipn.ui.util

import com.tailscale.ipn.ui.model.FavoriteItem
import com.tailscale.ipn.ui.model.Favorites
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesTest {

  private fun ids(favorites: Favorites) = favorites.devices?.mapNotNull { it.id }

  @Test
  fun emptyFavoritesHasNoPinnedDevices() {
    val favorites = Favorites()

    assertTrue(favorites.deviceIds.isEmpty())
    assertFalse(favorites.isFavoriteDevice("n1"))
  }

  @Test
  fun deviceIdsSkipsEntriesWithoutAnId() {
    val favorites =
        Favorites(
            devices =
                listOf(
                    FavoriteItem(id = "n1"),
                    FavoriteItem(name = "no id"),
                    FavoriteItem(id = "n2"),
                ))

    // .toSet() so this holds whether deviceIds stays a List or becomes a Set.
    assertEquals(setOf("n1", "n2"), favorites.deviceIds.toSet())
  }

  @Test
  fun togglingAnUnpinnedDeviceAddsIt() {
    val request = Favorites(devices = listOf(FavoriteItem(id = "n1"))).withToggledDevice("n2")

    assertEquals(listOf("n1", "n2"), ids(request.pins))
  }

  @Test
  fun togglingAPinnedDeviceRemovesIt() {
    val favorites = Favorites(devices = listOf(FavoriteItem(id = "n1"), FavoriteItem(id = "n2")))

    assertEquals(listOf("n2"), ids(favorites.withToggledDevice("n1").pins))
  }

  @Test
  fun toggleOnlyMarksDevicesAsSet() {
    val request = Favorites().withToggledDevice("n1")

    assertEquals(true, request.devicesSet)
    assertNull(request.exitNodesSet)
    assertNull(request.servicesSet)
  }

  @Test
  fun togglePreservesExitNodesAndServices() {
    val favorites =
        Favorites(
            devices = listOf(FavoriteItem(id = "n1")),
            exitNodes = listOf(FavoriteItem(id = "x1")),
            services = listOf(FavoriteItem(id = "s1")),
        )

    val request = favorites.withToggledDevice("n1")

    assertEquals(listOf("x1"), request.pins.exitNodes?.map { it.id })
    assertEquals(listOf("s1"), request.pins.services?.map { it.id })
  }

  // Locks the exact bytes Client.setFavorites puts on the wire. Client uses the
  // default Json instance (encodeDefaults = false), which is what makes the
  // null *Set flags and the unset ExitNodes/Services drop out.
  @Test
  fun pinRequestSerializesToTheExpectedJson() {
    val request = Favorites().withToggledDevice("nodeA")

    assertEquals(
        """{"Pins":{"Devices":[{"ID":"nodeA"}]},"DevicesSet":true}""",
        Json.encodeToString(request),
    )
  }

  @Test
  fun unpinningTheLastDeviceSendsAnExplicitEmptyList() {
    // An omitted Devices key would mean "no change" to the backend, so the
    // empty list has to survive serialization.
    val request = Favorites(devices = listOf(FavoriteItem(id = "nodeA"))).withToggledDevice("nodeA")

    assertEquals("""{"Pins":{"Devices":[]},"DevicesSet":true}""", Json.encodeToString(request))
  }

  @Test
  fun parsesAGetPinsResponse() {
    // Client decodes with ignoreUnknownKeys = true; mirror that here.
    val json = """{"Devices":[{"ID":"nodeA"}],"ExitNodes":null,"Services":[],"Unknown":1}"""

    val favorites = Json { ignoreUnknownKeys = true }.decodeFromString<Favorites>(json)

    assertTrue(favorites.isFavoriteDevice("nodeA"))
    assertEquals("nodeA", favorites.devices?.single()?.id)
    assertNull(favorites.exitNodes)
    assertEquals(emptyList<FavoriteItem>(), favorites.services)
  }
}
