// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogTest {
  private val policy = WatchdogPolicy()

  /** Feeds probe rounds into a [WatchdogCore] while letting the test control the clock. */
  private class Driver(val core: WatchdogCore, val policy: WatchdogPolicy) {
    var now = 0L

    fun failRounds(count: Int, targets: Int): WatchdogDecision {
      var decision: WatchdogDecision = WatchdogDecision.Waiting
      repeat(count) { decision = core.onProbeRound(false, targets, now) }
      return decision
    }

    fun advance(ms: Long) {
      now += ms
    }
  }

  @Test
  fun failureCounterRequiresThreeConsecutiveFailures() {
    val core = WatchdogCore(policy)

    assertEquals(WatchdogDecision.Waiting, core.onProbeRound(false, 2, 0))
    assertEquals(WatchdogDecision.Waiting, core.onProbeRound(false, 2, 0))

    val third = core.onProbeRound(false, 2, 0)
    assertTrue("third failure should act, got $third", third is WatchdogDecision.Act)
    assertEquals(RecycleAction.REBIND, (third as WatchdogDecision.Act).action)
  }

  @Test
  fun successResetsFailureCounter() {
    val core = WatchdogCore(policy)

    core.onProbeRound(false, 2, 0)
    core.onProbeRound(false, 2, 0)
    assertEquals(WatchdogDecision.Healthy, core.onProbeRound(true, 2, 0))

    assertEquals(WatchdogDecision.Waiting, core.onProbeRound(false, 2, 0))
    assertEquals(WatchdogDecision.Waiting, core.onProbeRound(false, 2, 0))
    assertTrue(core.onProbeRound(false, 2, 0) is WatchdogDecision.Act)
  }

  @Test
  fun escalationOrderIsCheapestFirst() {
    val core = WatchdogCore(policy)
    val driver = Driver(core, policy)
    val actions = mutableListOf<RecycleAction>()

    repeat(4) {
      val decision = driver.failRounds(policy.failureThreshold, policy.minProbeTargets)
      assertTrue("expected an action, got $decision", decision is WatchdogDecision.Act)
      actions.add((decision as WatchdogDecision.Act).action)
      driver.advance(policy.cooldownMillis + 1)
    }

    assertEquals(
        listOf(
            RecycleAction.REBIND,
            RecycleAction.RESTUN,
            RecycleAction.BREAK_TCP_CONNS,
            RecycleAction.RESTART_VPN,
        ),
        actions,
    )
  }

  @Test
  fun restartCooldownBlocksASecondRestart() {
    val core = WatchdogCore(policy)
    val driver = Driver(core, policy)

    // Walk the ladder to the first restart.
    repeat(4) {
      driver.failRounds(policy.failureThreshold, policy.minProbeTargets)
      driver.advance(policy.cooldownMillis + 1)
    }
    val firstRestartAt = driver.now - (policy.cooldownMillis + 1)

    // Just after the restart, a fresh set of failures must not restart again.
    driver.now = firstRestartAt + 1
    assertEquals(
        WatchdogDecision.Waiting,
        driver.failRounds(policy.failureThreshold, policy.minProbeTargets),
    )

    // Once the cooldown has elapsed, the very next failed round restarts again. The failure
    // counter is still at the threshold from the blocked round, so a single round is enough.
    driver.now = firstRestartAt + policy.cooldownMillis + 1
    val decision = core.onProbeRound(false, policy.minProbeTargets, driver.now)
    assertTrue("expected a restart, got $decision", decision is WatchdogDecision.Act)
    assertEquals(RecycleAction.RESTART_VPN, (decision as WatchdogDecision.Act).action)
  }

  @Test
  fun givesUpAfterMaxRestarts() {
    val core = WatchdogCore(policy)
    val driver = Driver(core, policy)
    var gaveUp = false
    var restarts = 0

    repeat(12) {
      when (val decision = driver.failRounds(policy.failureThreshold, policy.minProbeTargets)) {
        is WatchdogDecision.Act ->
            if (decision.action == RecycleAction.RESTART_VPN) restarts++
        WatchdogDecision.GiveUp -> gaveUp = true
        else -> {}
      }
      driver.advance(policy.cooldownMillis + 1)
    }

    assertTrue("watchdog never gave up", gaveUp)
    assertEquals(policy.maxRestarts, restarts)
  }

  @Test
  fun insufficientTargetsNeverActs() {
    val core = WatchdogCore(policy)

    repeat(10) { assertEquals(WatchdogDecision.Waiting, core.onProbeRound(false, 1, 0)) }

    assertFalse(core.isGivenUp)
  }

  @Test
  fun recoveryAfterEscalationIsReported() {
    val core = WatchdogCore(policy)

    repeat(3) { core.onProbeRound(false, 2, 0) }

    assertEquals(WatchdogDecision.Recovered, core.onProbeRound(true, 2, 0))
    assertEquals(WatchdogDecision.Healthy, core.onProbeRound(true, 2, 0))
  }

  @Test
  fun noActionAfterGiveUp() {
    val core = WatchdogCore(policy)
    val driver = Driver(core, policy)

    repeat(12) {
      driver.failRounds(policy.failureThreshold, policy.minProbeTargets)
      driver.advance(policy.cooldownMillis + 1)
    }

    assertTrue(core.isGivenUp)
    assertEquals(
        WatchdogDecision.Waiting,
        driver.failRounds(policy.failureThreshold, policy.minProbeTargets),
    )
  }
}
