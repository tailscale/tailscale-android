// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// DESTINATION: android/src/test/kotlin/com/tailcale/ipn/ui/util/PeerCategorizerTest.kt
// (yes, "tailcale" -- the existing unit-test package root is misspelled; see
// TimeUtilTest.kt and HealthNotifierTest.kt.)
//
// Run with:  cd android && ./gradlew test --tests '*PeerCategorizerTest*'

package com.tailcale.ipn.ui

import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.ui.model.FavoriteItem
import com.tailscale.ipn.ui.model.Favorites
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.util.PeerCategorizer
import com.tailscale.ipn.ui.util.PeerSet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

// PeerHelper.kt keeps FAVORITES_ID private to the file, so it has to be
// duplicated here. Once it moves to PeerSet.PINNED_ID, delete this and use
// PeerSet.PINNED_ID directly.
private const val PINNED_ID = -1L

private const val ME_ID = 1L
private const val BOB_ID = 2L
private const val ZEKE_ID = 3L
private const val TAGGED_ID = 4L

private fun node(
    stableId: String,
    computedName: String,
    user: Long,
    address: String,
) =
    Tailcfg.Node(
        StableID = stableId,
        Name = "$computedName.example.ts.net.",
        User = user,
        ComputedName = computedName,
        Addresses = listOf(address),
    )

private fun profile(id: Long, displayName: String, loginName: String) =
    Tailcfg.UserProfile(ID = id, DisplayName = displayName, LoginName = loginName)

private fun pins(vararg stableIds: String) =
    Favorites(devices = stableIds.map { FavoriteItem(id = it) })

class PeerCategorizerTest {

  private val self = node("self", "my-phone", ME_ID, "100.64.0.1/32")
  private val delta = node("d1", "Delta", ME_ID, "100.64.0.2/32")
  private val alpha = node("a1", "alpha", ME_ID, "100.64.0.3/32")
  private val bravo = node("b1", "bravo", BOB_ID, "100.64.0.4/32")
  private val zulu = node("z1", "zulu", ZEKE_ID, "100.64.0.5/32")
  private val taggedBox = node("t1", "tagged-box", TAGGED_ID, "100.64.0.6/32")

  // isMullvadNode matches on Name/ComputedName suffix; Name carries a trailing
  // dot here, so the suffix has to be on ComputedName to be detected.
  private val mullvad = node("mv", "se-sto-wg-001.mullvad.ts.net", BOB_ID, "100.64.0.7/32")

  private val netmap =
      Netmap.NetworkMap(
          SelfNode = self,
          Peers = listOf(delta, alpha, bravo, zulu, taggedBox, mullvad),
          Domain = "example.ts.net",
          UserProfiles =
              mapOf(
                  ME_ID.toString() to profile(ME_ID, "alice", "alice@example.com"),
                  BOB_ID.toString() to profile(BOB_ID, "bob", "bob@example.com"),
                  ZEKE_ID.toString() to profile(ZEKE_ID, "zeke", "zeke@example.com"),
                  TAGGED_ID.toString() to profile(TAGGED_ID, "tagged-devices", "tagged-devices"),
              ),
          TKAEnabled = false,
      )

  private lateinit var categorizer: PeerCategorizer

  @Before
  fun setUp() {
    categorizer = PeerCategorizer()
    clearHiddenDevices()
  }

  // MDMSettings is a process-wide singleton, so leaving it set would leak into
  // whichever test class runs next in the same JVM.
  @After
  fun tearDown() {
    clearHiddenDevices()
  }

  private fun hideDevices(vararg categories: String) {
    MDMSettings.hiddenNetworkDevices.flow.value = SettingState(categories.toList(), true)
  }

  private fun clearHiddenDevices() {
    MDMSettings.hiddenNetworkDevices.flow.value = SettingState(null, false)
  }

  private fun regenerate(favorites: Favorites = Favorites()) =
      categorizer.regenerateGroupedPeers(netmap, favorites)

  private fun sectionIds() = categorizer.peerSets.map { it.id }

  private fun section(id: Long): PeerSet = categorizer.peerSets.first { it.id == id }

  private fun stableIds(peerSet: PeerSet) = peerSet.nodes.map { it.StableID }

  // ---------------------------------------------------------------- grouping

  @Test
  fun withoutPins_currentUserSectionSortsFirstThenAlphabetically() {
    regenerate()

    assertEquals(listOf(ME_ID, BOB_ID, TAGGED_ID, ZEKE_ID), sectionIds())
    assertEquals(
        listOf("alice", "bob", "tagged-devices", "zeke"),
        categorizer.peerSets.map { it.title },
    )
  }

  @Test
  fun withinASection_selfNodeIsFirstThenCaseInsensitiveAlphabetical() {
    regenerate()

    // "alpha" must sort before "Delta" -- a case-sensitive compare would put
    // "Delta" first because 'D' < 'a' in ASCII.
    assertEquals(listOf("self", "a1", "d1"), stableIds(section(ME_ID)))
  }

  @Test
  fun mullvadNodesAreExcludedEntirely() {
    regenerate()

    assertTrue(categorizer.peerSets.none { set -> set.nodes.any { it.StableID == "mv" } })
  }

  @Test
  fun withoutPins_noPinnedSectionIsEmitted() {
    regenerate(Favorites())
    assertTrue(sectionIds().none { it == PINNED_ID })

    regenerate(Favorites(devices = emptyList()))
    assertTrue(sectionIds().none { it == PINNED_ID })
  }

  @Test
  fun pinnedSectionSortsFirstAndHasANullTitle() {
    regenerate(pins("z1", "d1"))

    val pinned = categorizer.peerSets.first()
    assertEquals(PINNED_ID, pinned.id)
    assertNull("Pinned section resolves its title from a string resource", pinned.title)
    assertEquals(listOf("d1", "z1"), stableIds(pinned)) // Delta, zulu
  }

  @Test
  fun pinnedNodesAreMovedOutOfTheirOwnerSection() {
    // Documents the current "move, don't duplicate" behaviour. Confirm this
    // matches darwin before locking it in.
    regenerate(pins("z1", "d1"))

    assertEquals(listOf("self", "a1"), stableIds(section(ME_ID)))
    // zeke owned only z1, so their section disappears completely.
    assertTrue(sectionIds().none { it == ZEKE_ID })
  }

  @Test
  fun selfNodeStillSortsFirstInsideThePinnedSection() {
    regenerate(pins("self", "d1", "z1"))

    assertEquals(listOf("self", "d1", "z1"), stableIds(section(PINNED_ID)))
  }

  // ------------------------------------------------------------- MDM filters

  @Test
  fun hideTaggedDevices_hidesUnpinnedTaggedDevices() {
    hideDevices("tagged-devices")
    regenerate()

    assertTrue(sectionIds().none { it == TAGGED_ID })
  }

  @Test
  fun hideOtherDevices_keepsMyOwnPinnedDevices() {
    // d1 is grouped under the pinned id, so a "userId != me.ID" check would
    // hide it even though I own it. Filter on peer.User, group on the pinned id.
    hideDevices("other-users")
    regenerate(pins("d1"))

    assertEquals(listOf("d1"), stableIds(section(PINNED_ID)))
  }

  @Test
  fun hideMyDevices_alsoHidesMyPinnedDevices() {
    // A "userId == me.ID" check can never match a pinned node, so d1 would
    // leak into the Pinned section.
    hideDevices("current-user")
    regenerate(pins("d1"))

    assertTrue(categorizer.peerSets.none { set -> set.nodes.any { it.StableID == "d1" } })
  }

  @Test
  fun hideTaggedDevices_alsoHidesPinnedTaggedDevices() {
    // userProfile(pinnedId) is null, so isTaggedDevice() would never be
    // consulted for a pinned node.
    hideDevices("tagged-devices")
    regenerate(pins("t1"))

    assertTrue(categorizer.peerSets.none { set -> set.nodes.any { it.StableID == "t1" } })
  }

  // ----------------------------------------------------------------- search

  @Test
  fun searchMatchesNodeNameInsideThePinnedSection() {
    regenerate(pins("z1"))

    val result = categorizer.groupedAndFilteredPeers("zulu")

    assertEquals(listOf(PINNED_ID), result.map { it.id })
    assertEquals(listOf("z1"), stableIds(result.single()))
  }

  @Test
  fun searchMatchesAddress() {
    regenerate()

    val result = categorizer.groupedAndFilteredPeers("100.64.0.5")

    assertEquals(listOf("z1"), stableIds(result.single()))
  }

  @Test
  fun searchMatchingAUserNameReturnsTheWholeSection() {
    regenerate()

    val result = categorizer.groupedAndFilteredPeers("ali")

    assertEquals(listOf(ME_ID), result.map { it.id })
    assertEquals(listOf("self", "a1", "d1"), stableIds(result.single()))
  }

  @Test
  fun incrementalSearchNarrowsThePreviousResult() {
    regenerate()

    // "ta" matches the "tagged-devices" section title, so the whole section
    // comes back and is cached as lastSearchResult.
    assertEquals(listOf(TAGGED_ID), categorizer.groupedAndFilteredPeers("tagg").map { it.id })

    // "tagged-box" no longer matches the title but does match the node, and is
    // resolved against the cached result rather than all peerSets.
    val result = categorizer.groupedAndFilteredPeers("tagged-box")
    assertEquals(listOf("t1"), stableIds(result.single()))
  }

  @Test
  fun regeneratingInvalidatesTheSearchCache() {
    // FAILS until regenerateGroupedPeers() resets lastSearchTerm /
    // lastSearchResult. MainViewModel.categorize() calls
    // groupedAndFilteredPeers(searchTerm.value) right after regenerating, and
    // it short-circuits on the unchanged term and returns the pre-pin result.
    regenerate()
    assertEquals(listOf(ZEKE_ID), categorizer.groupedAndFilteredPeers("zulu").map { it.id })

    regenerate(pins("z1"))
    val result = categorizer.groupedAndFilteredPeers("zulu")

    assertEquals(listOf(PINNED_ID), result.map { it.id })
  }

  @Ignore("Design decision: should a user-name search still reach that user's pinned devices?")
  @Test
  fun searchingAUserNameStillFindsTheirPinnedDevices() {
    // The pinned PeerSet has a null title, so a user-name search can't reach
    // it. Because bob's only other node is a Mullvad node, searching "bob"
    // returns nothing at all once b1 is pinned.
    regenerate(pins("b1"))

    val result = categorizer.groupedAndFilteredPeers("bob")

    assertEquals(listOf("b1"), result.flatMap { stableIds(it) })
  }
}
