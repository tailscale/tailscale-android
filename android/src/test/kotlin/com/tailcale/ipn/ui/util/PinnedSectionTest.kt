// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailcale.ipn.ui.util

import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.withPinnedSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

private const val ALICE_ID = 1L
private const val BOB_ID = 2L
private const val ZEKE_ID = 3L

private fun node(stableId: String, computedName: String) =
    Tailcfg.Node(
        StableID = stableId,
        Name = "$computedName.example.ts.net.",
        ComputedName = computedName,
    )

// withPinnedSection is a pure list transform: no netmap, no MDM singleton, no ordering
// rules of its own beyond the pin order handed to it.
class PinnedSectionTest {

  private val alpha = node("a1", "alpha")
  private val delta = node("d1", "Delta")
  private val bravo = node("b1", "bravo")
  private val zulu = node("z1", "zulu")

  private val sets =
      listOf(
          PeerSet(ALICE_ID, "alice", listOf(alpha, delta)),
          PeerSet(BOB_ID, "bob", listOf(bravo)),
          PeerSet(ZEKE_ID, "zeke", listOf(zulu)),
      )

  private fun stableIds(peerSet: PeerSet) = peerSet.nodes.map { it.StableID }

  @Test
  fun noPinsReturnsTheInputUnchanged() {
    assertSame(sets, sets.withPinnedSection(emptyList()))
  }

  @Test
  fun pinnedSectionSortsFirstAndHasANullTitle() {
    val result = sets.withPinnedSection(listOf("z1"))

    val pinned = result.first()
    assertEquals(PeerSet.FAVORITES_ID, pinned.id)
    // The title is resolved from a string resource at render time (PeerSet.sectionTitle).
    assertNull(pinned.title)
  }

  @Test
  fun pinnedSectionUsesPinOrderNotAlphabetical() {
    // Backend list order is the user's display order. Alphabetically this would be
    // ["d1", "z1"]; the pin list says otherwise.
    val result = sets.withPinnedSection(listOf("z1", "d1"))

    assertEquals(listOf("z1", "d1"), stableIds(result.first()))
  }

  @Test
  fun pinnedNodesAreRemovedFromTheirOwnerSection() {
    // Move, don't duplicate -- matching NodeSearcher.prependFavoritesSection on darwin.
    // Also required by LazyColumn, which rejects a duplicated item key.
    val result = sets.withPinnedSection(listOf("d1"))

    assertEquals(listOf("a1"), stableIds(result.first { it.id == ALICE_ID }))
  }

  @Test
  fun ownerSectionDisappearsWhenAllOfItsNodesArePinned() {
    // zeke owns only z1, so pinning it empties their section entirely.
    val result = sets.withPinnedSection(listOf("z1"))

    assertEquals(listOf(PeerSet.FAVORITES_ID, ALICE_ID, BOB_ID), result.map { it.id })
  }

  @Test
  fun pinsForNodesNotInTheNetmapAreSkipped() {
    val result = sets.withPinnedSection(listOf("gone", "z1"))

    assertEquals(listOf("z1"), stableIds(result.first()))
  }

  @Test
  fun allStalePinsReturnTheInputUnchanged() {
    assertSame(sets, sets.withPinnedSection(listOf("gone", "alsoGone")))
  }

  @Test
  fun aDuplicatedPinIdYieldsTheNodeOnce() {
    // A duplicated id from the backend would otherwise produce two rows with the same
    // StableID, which LazyColumn throws on ("Key was already used").
    val result = sets.withPinnedSection(listOf("d1", "d1"))

    assertEquals(listOf("d1"), stableIds(result.first()))
  }
}
