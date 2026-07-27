// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import androidx.core.app.NotificationManagerCompat
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.ui.model.Health
import com.tailscale.ipn.ui.model.Health.UnhealthyState
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.util.TSLog.LibtailscaleWrapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class HealthNotifierTest {

  private lateinit var originalWrapper: LibtailscaleWrapper

  private fun derpWarning() =
      UnhealthyState(
          WarnableCode = "no-derp-connection",
          Severity = Health.Severity.medium,
          Title = "Relay server unavailable",
          Text = "Could not connect to relay server.",
          ImpactsConnectivity = true,
          DependsOn = listOf("network-status", "no-derp-home", "warming-up"),
      )

  private fun warmingUpWarning() =
      UnhealthyState(
          WarnableCode = "warming-up",
          Severity = Health.Severity.low,
          Title = "Starting",
          Text = "Tailscale is starting.",
      )

  private fun healthState(vararg warnings: UnhealthyState): Health.State {
    return Health.State(Warnings = warnings.associateBy { it.WarnableCode })
  }

  private fun emptyHealth(): Health.State = Health.State(Warnings = emptyMap())

  @Before
  fun setUp() {
    val logMock = mock(LibtailscaleWrapper::class.java)
    doNothing().`when`(logMock).sendLog(anyString(), anyString())
    originalWrapper = TSLog.libtailscaleWrapper
    TSLog.libtailscaleWrapper = logMock

    UninitializedApp.notificationManager = mock(NotificationManagerCompat::class.java)
  }

  @After
  fun tearDown() {
    TSLog.libtailscaleWrapper = originalWrapper
  }

  private fun kotlinx.coroutines.test.TestScope.settle() {
    advanceTimeBy(4000)
    runCurrent()
  }

  private fun kotlinx.coroutines.test.TestScope.createRunningNotifier():
      Triple<MutableStateFlow<Health.State?>, MutableStateFlow<Ipn.State>, HealthNotifier> {
    val healthFlow = MutableStateFlow<Health.State?>(null)
    val ipnFlow = MutableStateFlow(Ipn.State.Running)
    val notifier = HealthNotifier(healthFlow, ipnFlow, backgroundScope)
    runCurrent()
    healthFlow.value = emptyHealth()
    settle()
    return Triple(healthFlow, ipnFlow, notifier)
  }

  @Test
  fun warningShownWhileRunningClearsAfterDebounce() = runTest {
    val (healthFlow, _, notifier) = createRunningNotifier()

    healthFlow.value = healthState(derpWarning())
    settle()
    assertEquals(1, notifier.currentWarnings.value.size)

    healthFlow.value = emptyHealth()
    settle()
    assertTrue(notifier.currentWarnings.value.isEmpty())
  }

  @Test
  fun warningsDroppedWhenStateLeavesRunning() = runTest {
    val (healthFlow, ipnFlow, notifier) = createRunningNotifier()

    healthFlow.value = healthState(derpWarning())
    settle()
    assertEquals(1, notifier.currentWarnings.value.size)

    ipnFlow.value = Ipn.State.Stopped
    runCurrent()
    assertTrue(notifier.currentWarnings.value.isEmpty())
  }

  @Test
  fun noWarningsWhileStoppedEvenWithUnhealthyState() = runTest {
    val healthFlow = MutableStateFlow<Health.State?>(null)
    val ipnFlow = MutableStateFlow(Ipn.State.Stopped)
    val notifier = HealthNotifier(healthFlow, ipnFlow, backgroundScope)
    runCurrent()

    healthFlow.value = healthState(derpWarning())
    settle()
    assertTrue(notifier.currentWarnings.value.isEmpty())
  }

  @Test
  fun staleWarningFromStoppedDoesNotPersistAfterToggleOn() = runTest {
    val (healthFlow, ipnFlow, notifier) = createRunningNotifier()

    ipnFlow.value = Ipn.State.Stopped
    runCurrent()
    healthFlow.value = healthState(derpWarning())
    settle()
    assertTrue("Should have no warnings while Stopped", notifier.currentWarnings.value.isEmpty())

    healthFlow.value = emptyHealth()
    settle()

    ipnFlow.value = Ipn.State.Running
    settle()
    assertTrue(
        "Stale DERP warning should NOT appear after toggle on",
        notifier.currentWarnings.value.isEmpty(),
    )
  }

  @Test
  fun derpFlappingWhileStoppedThenToggleOnShowsNoStaleWarning() = runTest {
    val (healthFlow, ipnFlow, notifier) = createRunningNotifier()

    ipnFlow.value = Ipn.State.Stopped
    runCurrent()

    for (i in 1..5) {
      healthFlow.value = healthState(derpWarning())
      advanceTimeBy(1000)
      healthFlow.value = emptyHealth()
      advanceTimeBy(1000)
    }
    healthFlow.value = healthState(derpWarning())
    settle()
    assertTrue("No warnings while Stopped", notifier.currentWarnings.value.isEmpty())

    ipnFlow.value = Ipn.State.Running
    healthFlow.value = emptyHealth()
    settle()
    assertTrue(
        "Should not show stale DERP warning",
        notifier.currentWarnings.value.isEmpty(),
    )
  }

  @Test
  fun realDerpFailureAfterToggleOnShowsWarning() = runTest {
    val (healthFlow, _, notifier) = createRunningNotifier()

    healthFlow.value = healthState(derpWarning())
    settle()
    assertEquals(
        "Should show DERP warning for real failure",
        setOf("no-derp-connection"),
        notifier.currentWarnings.value.map { it.WarnableCode }.toSet(),
    )
  }

  @Test
  fun warmingUpWarningsAreIgnored() = runTest {
    val (healthFlow, _, notifier) = createRunningNotifier()

    healthFlow.value = healthState(warmingUpWarning(), derpWarning())
    settle()
    assertTrue(
        "DERP warning should be hidden by warming-up dependency",
        notifier.currentWarnings.value.none { it.WarnableCode == "no-derp-connection" },
    )
  }

  @Test
  fun ignoredWarnableCodesAreFiltered() = runTest {
    val (healthFlow, _, notifier) = createRunningNotifier()

    val unstableWarning =
        UnhealthyState(
            WarnableCode = "is-using-unstable-version",
            Severity = Health.Severity.low,
            Title = "Unstable",
            Text = "Using unstable version",
        )
    healthFlow.value = healthState(unstableWarning)
    settle()
    assertTrue(notifier.currentWarnings.value.isEmpty())
  }

  @Test
  fun iconSetForConnectivityImpactingWarning() = runTest {
    val (healthFlow, _, notifier) = createRunningNotifier()

    healthFlow.value = healthState(derpWarning())
    settle()
    assertTrue("Icon should not be null when warning present", notifier.currentIcon.value != null)

    healthFlow.value = emptyHealth()
    settle()
    assertNull("Icon should be null when no warnings", notifier.currentIcon.value)
  }

  @Test
  fun rapidStateTransitionsDoNotLeakStaleWarnings() = runTest {
    val healthFlow = MutableStateFlow<Health.State?>(null)
    val ipnFlow = MutableStateFlow(Ipn.State.Stopped)
    val notifier = HealthNotifier(healthFlow, ipnFlow, backgroundScope)
    runCurrent()

    healthFlow.value = healthState(derpWarning())
    settle()

    ipnFlow.value = Ipn.State.Running
    advanceTimeBy(500)
    ipnFlow.value = Ipn.State.Stopped
    advanceTimeBy(500)
    ipnFlow.value = Ipn.State.Running
    healthFlow.value = emptyHealth()
    settle()

    assertTrue(
        "No stale warnings after rapid toggling",
        notifier.currentWarnings.value.isEmpty(),
    )
  }
}
