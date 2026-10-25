// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.FavoriteItem
import com.tailscale.ipn.ui.model.Favorites
import com.tailscale.ipn.ui.model.FavoritesRequest
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.util.TSLog
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedConstruction
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesManagerTest {
  private lateinit var clientConstruction: MockedConstruction<Client>
  private val loads = ArrayDeque<(Result<Favorites>) -> Unit>()
  private val writes = ArrayDeque<PendingWrite>()
  private val networkMap =
      Netmap.NetworkMap(
          SelfNode = Tailcfg.Node(StableID = "self"),
          Domain = "example.ts.net",
          UserProfiles = emptyMap(),
          TKAEnabled = false,
      )
  private val netmap = MutableStateFlow<Netmap.NetworkMap?>(networkMap)
  private lateinit var originalLogWrapper: TSLog.LibtailscaleWrapper

  private data class PendingWrite(
      val request: FavoritesRequest,
      val complete: (Result<Favorites>) -> Unit,
  ) {
    fun succeed() = complete(Result.success(request.pins))

    fun fail() = complete(Result.failure(Exception("write failed")))
  }

  @Before
  fun setUp() {
    originalLogWrapper = TSLog.libtailscaleWrapper
    TSLog.libtailscaleWrapper = mock()

    val profile =
        IpnLocal.LoginProfile(
            ID = "profile-1",
            Name = "test",
            Key = "key",
            UserProfile = Tailcfg.UserProfile(),
            LocalUserID = "user-1",
        )
    clientConstruction =
        mockConstruction(Client::class.java) { client, _ ->
          doAnswer {
                it.getArgument<(Result<IpnLocal.LoginProfile>) -> Unit>(0)(Result.success(profile))
                null
              }
              .whenever(client)
              .currentProfile(any())
          doAnswer {
                loads.addLast(it.getArgument(0))
                null
              }
              .whenever(client)
              .getFavorites(any())
          doAnswer {
                writes.addLast(PendingWrite(it.getArgument(0), it.getArgument(1)))
                null
              }
              .whenever(client)
              .setFavorites(any(), any())
        }
  }

  @After
  fun tearDown() {
    clientConstruction.close()
    TSLog.libtailscaleWrapper = originalLogWrapper
  }

  private fun favorites(vararg ids: String) = Favorites(devices = ids.map { FavoriteItem(id = it) })

  private fun TestScope.createManager(initial: Favorites = favorites()): FavoritesManager {
    val manager =
        FavoritesManager(
            ipnStateFlow = MutableStateFlow(Ipn.State.Running),
            netmapFlow = netmap,
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            writeDebounce = 350.milliseconds,
            retryDelay = 2000.milliseconds,
        )
    runCurrent()
    assertEquals(1, loads.size)
    loads.removeFirst()(Result.success(initial))
    runCurrent()
    assertEquals(initial, manager.favorites.value)
    return manager
  }

  private fun TestScope.finishDebounce() {
    runCurrent()
    advanceTimeBy(350.milliseconds)
    runCurrent()
  }

  @Test
  fun consecutiveFailuresRollBackToLoadedFavorites() = runTest {
    val initial = favorites("existing")
    val manager = createManager(initial)
    manager.toggleDevice("A")
    finishDebounce()
    val first = writes.removeFirst()
    assertTrue(manager.writing.value)

    manager.toggleDevice("B")
    runCurrent()
    first.fail()
    runCurrent()
    assertEquals(favorites("existing", "A", "B"), manager.favorites.value)

    finishDebounce()
    writes.removeFirst().fail()
    runCurrent()
    assertEquals(initial, manager.favorites.value)
    assertFalse(manager.writing.value)
  }

  @Test
  fun failureAfterSuccessRollsBackToConfirmedWrite() = runTest {
    val manager = createManager()
    manager.toggleDevice("A")
    finishDebounce()
    val first = writes.removeFirst()

    manager.toggleDevice("B")
    runCurrent()
    first.succeed()
    runCurrent()
    assertEquals(favorites("A", "B"), manager.favorites.value)

    finishDebounce()
    writes.removeFirst().fail()
    runCurrent()
    assertEquals(favorites("A"), manager.favorites.value)
    assertFalse(manager.writing.value)
  }

  @Test
  fun successAfterFailureKeepsLatestFavorites() = runTest {
    val manager = createManager()
    manager.toggleDevice("A")
    finishDebounce()
    val first = writes.removeFirst()

    manager.toggleDevice("B")
    runCurrent()
    first.fail()
    runCurrent()
    finishDebounce()
    val second = writes.removeFirst()
    assertEquals(favorites("A", "B"), second.request.pins)
    second.succeed()
    runCurrent()
    assertEquals(second.request.pins, manager.favorites.value)
    assertFalse(manager.writing.value)
  }

  @Test
  fun successfulWritesPreserveLatestOptimisticFavorites() = runTest {
    val manager = createManager()
    manager.toggleDevice("A")
    finishDebounce()
    val first = writes.removeFirst()

    manager.toggleDevice("B")
    runCurrent()
    first.succeed()
    runCurrent()
    assertEquals(favorites("A", "B"), manager.favorites.value)

    finishDebounce()
    writes.removeFirst().succeed()
    runCurrent()
    assertEquals(favorites("A", "B"), manager.favorites.value)
    assertFalse(manager.writing.value)
  }

  @Test
  fun queuedWriteFailureDoesNotRollBackNewerDebouncingEdit() = runTest {
    val manager = createManager()
    manager.toggleDevice("A")
    finishDebounce()
    val first = writes.removeFirst()

    // B is queued behind A, while C has not finished debouncing when B starts.
    manager.toggleDevice("B")
    finishDebounce()
    assertTrue(writes.isEmpty())
    manager.toggleDevice("C")
    runCurrent()
    first.fail()
    runCurrent()
    val second = writes.removeFirst()
    assertEquals(favorites("A", "B"), second.request.pins)
    second.fail()
    runCurrent()
    assertEquals(favorites("A", "B", "C"), manager.favorites.value)

    finishDebounce()
    val third = writes.removeFirst()
    assertEquals(favorites("A", "B", "C"), third.request.pins)
    third.succeed()
    runCurrent()
    assertEquals(third.request.pins, manager.favorites.value)
    assertFalse(manager.writing.value)
  }

  @Test
  fun staleLoadAfterResetDoesNotOverwriteSavedFavorites() = runTest {
    val manager = createManager()
    netmap.value = null
    runCurrent()
    val staleLoad = loads.removeFirst()
    assertNull(manager.favorites.value)

    // The node returns and resolves to the same profile, opening a new generation.
    netmap.value = networkMap
    runCurrent()
    loads.removeFirst()(Result.success(favorites()))
    runCurrent()
    manager.toggleDevice("B")
    finishDebounce()
    writes.removeFirst().succeed()
    runCurrent()

    staleLoad(Result.success(favorites()))
    runCurrent()
    assertEquals(favorites("B"), manager.favorites.value)

    // The stale load must not replace the rollback baseline either.
    manager.toggleDevice("C")
    finishDebounce()
    writes.removeFirst().fail()
    runCurrent()
    assertEquals(favorites("B"), manager.favorites.value)
    assertFalse(manager.writing.value)
  }

  @Test
  fun resetDropsQueuedWritesAndIgnoresOldWriteSuccess() = runTest {
    val manager = createManager()
    manager.toggleDevice("A")
    finishDebounce()
    val first = writes.removeFirst()
    manager.toggleDevice("B")
    finishDebounce()
    assertTrue(writes.isEmpty())

    netmap.value = null
    runCurrent()
    loads.removeFirst()(Result.success(favorites("fresh")))
    runCurrent()
    assertFalse(manager.writing.value)
    manager.toggleDevice("C")
    finishDebounce()
    assertTrue(writes.isEmpty())

    // The old response arrives after a newer load for the same profile.
    first.succeed()
    runCurrent()
    assertEquals(1, writes.size)
    val current = writes.removeFirst()
    assertEquals(favorites("fresh", "C"), current.request.pins)
    assertTrue(manager.writing.value)
    current.fail()
    runCurrent()
    assertEquals(favorites("fresh"), manager.favorites.value)
    assertFalse(manager.writing.value)
  }

  @Test
  fun resetPreventsOldLoadRetryFromStarting() = runTest {
    val manager = createManager()
    netmap.value = null
    runCurrent()
    loads.removeFirst()(Result.failure(Exception("load failed")))
    runCurrent()

    advanceTimeBy(1000.milliseconds)
    netmap.value = networkMap
    runCurrent()
    loads.removeFirst()(Result.failure(Exception("new load failed")))
    runCurrent()

    // The old generation's retry is due now; the new generation's retry is due later.
    advanceTimeBy(1000.milliseconds)
    runCurrent()
    assertTrue(loads.isEmpty())
    advanceTimeBy(1000.milliseconds)
    runCurrent()
    assertEquals(1, loads.size)
    loads.removeFirst()(Result.success(favorites("fresh")))
    runCurrent()
    assertEquals(favorites("fresh"), manager.favorites.value)
    assertFalse(manager.writing.value)
  }
}
