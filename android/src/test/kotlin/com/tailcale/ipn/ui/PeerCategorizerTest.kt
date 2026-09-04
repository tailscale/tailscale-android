// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailcale.ipn.ui

import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.util.PeerCategorizer
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.withPinnedSection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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

class PeerCategorizerTest {

  private val self = node("self", "my-phone", ME_ID, "100.64.0.1/32")
  private val delta = node("d1", "Delta", ME_ID, "100.64.0.2/32")
  private val alpha = node("a1", "alpha", ME_ID, "100.64.0.3/32")
  private val bravo = node("b1", "bravo", BOB_ID, "100.64.0.4/32")
  private val zulu = node("z1", "zulu", ZEKE_ID, "100.64.0.5/32")
  private val taggedBox = node("t1", "tagged-box", TAGGED_ID, "100.64.0.6/32")

  // isMullvadNode matches on Name/ComputedName suffix
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

  // Used to prove the search cache is invalidated by a regeneration.
  private val netmapWithoutZulu =
      netmap.copy(Peers = listOf(delta, alpha, bravo, taggedBox, mullvad))

  private lateinit var categorizer: PeerCategorizer

  @Before
  fun setUp() {
    categorizer = PeerCategorizer()
    clearHiddenDevices()
  }

  // MDMSettings is a process-wide singleton
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

  private fun regenerate(map: Netmap.NetworkMap = netmap) = categorizer.regenerateGroupedPeers(map)

  private fun sectionIds(sets: List<PeerSet> = categorizer.peerSets) = sets.map { it.id }

  private fun section(id: Long, sets: List<PeerSet> = categorizer.peerSets) = sets.first {
    it.id == id
  }

  private fun stableIds(peerSet: PeerSet) = peerSet.nodes.map { it.StableID }

  // ---------------------------------------------------------------- grouping

  @Test
  fun currentUserSectionSortsFirstThenAlphabetically() {
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

    // "alpha" must sort before "Delta"
    assertEquals(listOf("self", "a1", "d1"), stableIds(section(ME_ID)))
  }

  @Test
  fun mullvadNodesAreExcludedEntirely() {
    regenerate()

    assertTrue(categorizer.peerSets.none { set -> set.nodes.any { it.StableID == "mv" } })
  }

  // ------------------------------------------------------------- MDM filters
  //
  // MDM filtering runs here, before withPinnedSection ever sees the nodes

  @Test
  fun hideTaggedDevices_hidesTaggedDevices() {
    hideDevices("tagged-devices")
    regenerate()

    assertTrue(sectionIds().none { it == TAGGED_ID })
  }

  @Test
  fun hideOtherDevices_keepsMyOwnPinnedDevices() {
    hideDevices("other-users")
    regenerate()

    val sets = categorizer.peerSets.withPinnedSection(listOf("d1"))

    assertEquals(listOf("d1"), stableIds(section(PeerSet.FAVORITES_ID, sets)))
  }

  @Test
  fun hideMyDevices_alsoHidesMyPinnedDevices() {
    hideDevices("current-user")
    regenerate()

    val sets = categorizer.peerSets.withPinnedSection(listOf("d1"))

    assertTrue(sets.none { set -> set.nodes.any { it.StableID == "d1" } })
  }

  @Test
  fun hideTaggedDevices_alsoHidesPinnedTaggedDevices() {
    hideDevices("tagged-devices")
    regenerate()

    val sets = categorizer.peerSets.withPinnedSection(listOf("t1"))

    assertTrue(sets.none { set -> set.nodes.any { it.StableID == "t1" } })
  }

  // ----------------------------------------------------------------- search

  @Test
  fun searchMatchesNodeName() {
    regenerate()

    val result = categorizer.groupedAndFilteredPeers("zulu")

    assertEquals(listOf(ZEKE_ID), result.map { it.id })
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

    // "tagg" matches the "tagged-devices" section title, so the whole section
    // comes back and is cached as lastSearchResult.
    assertEquals(listOf(TAGGED_ID), categorizer.groupedAndFilteredPeers("tagg").map { it.id })

    // "tagged-box" no longer matches the title but does match the node, and is
    // resolved against the cached result rather than all peerSets.
    val result = categorizer.groupedAndFilteredPeers("tagged-box")
    assertEquals(listOf("t1"), stableIds(result.single()))
  }

  @Test
  fun regeneratingInvalidatesTheSearchCache() {
    regenerate()
    assertEquals(listOf(ZEKE_ID), categorizer.groupedAndFilteredPeers("zulu").map { it.id })

    regenerate(netmapWithoutZulu)

    assertTrue(categorizer.groupedAndFilteredPeers("zulu").isEmpty())
  }
}
