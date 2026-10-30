// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import com.tailscale.ipn.ui.model.Health
import com.tailscale.ipn.ui.model.Health.UnhealthyState
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.viewModel.LoginFlow.LoginError
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.util.TSLog.LibtailscaleWrapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class LoginFlowTest {

  // LoginFlow logs via TSLog, which requires the (Android-only) gojni native
  // library in production. Stub it out for JVM unit tests, like
  // HealthNotifierTest does.
  private lateinit var originalWrapper: LibtailscaleWrapper

  @Before
  fun setUp() {
    val logMock = mock(LibtailscaleWrapper::class.java)
    doNothing().`when`(logMock).sendLog(anyString(), anyString())
    originalWrapper = TSLog.libtailscaleWrapper
    TSLog.libtailscaleWrapper = logMock
  }

  @After
  fun tearDown() {
    TSLog.libtailscaleWrapper = originalWrapper
  }

  private fun healthWithLoginError(error: String?): Health.State? {
    if (error == null) {
      return Health.State(Warnings = emptyMap())
    }
    return Health.State(
        Warnings =
            mapOf(
                Health.WARNABLE_LOGIN_STATE to
                    UnhealthyState(
                        WarnableCode = Health.WARNABLE_LOGIN_STATE,
                        Severity = Health.Severity.medium,
                        Title = "Logged out",
                        Text = "You are logged out. The last login error was: $error",
                        Args = mapOf(Health.ARG_ERROR to error),
                    )
            ),
    )
  }

  private class TestHarness(scope: kotlinx.coroutines.test.TestScope) {
    val ipnState = MutableStateFlow(Ipn.State.NeedsLogin)
    val browseToURLCount = MutableStateFlow(0L)
    val loginFinishedCount = MutableStateFlow(0L)
    val health = MutableStateFlow<Health.State?>(null)
    val errors = mutableListOf<LoginError>()
    val loginFlow =
        LoginFlow(
            scope.backgroundScope,
            ipnState,
            browseToURLCount,
            loginFinishedCount,
            health,
        ) {
          errors.add(it)
        }
  }

  private fun kotlinx.coroutines.test.TestScope.harness() = TestHarness(this)

  /**
   * Advances past the health error confirmation window (and the health debounce), so pending
   * signals and confirmations are processed.
   */
  private fun kotlinx.coroutines.test.TestScope.settle() {
    advanceTimeBy(LoginFlow.BACKEND_ERROR_CONFIRM_MS + 1_000)
    runCurrent()
  }

  @Test
  fun authURLTransitionsToAuthenticatingAndCompletionResets() = runTest {
    val h = harness()
    h.loginFlow.begin(notifyUser = true)
    assertEquals(LoginPhase.STARTING, h.loginFlow.phase.value)

    h.loginFlow.startCompleted()
    assertEquals(LoginPhase.AWAITING_URL, h.loginFlow.phase.value)

    // The backend delivers the auth URL; the user signs in in the browser.
    h.browseToURLCount.value = 1
    settle()
    assertEquals(LoginPhase.AUTHENTICATING, h.loginFlow.phase.value)

    // The login completes; the machine resets so the next login starts fresh.
    h.ipnState.value = Ipn.State.Running
    settle()
    assertEquals(LoginPhase.IDLE, h.loginFlow.phase.value)
    assertTrue(h.errors.isEmpty())
  }

  /**
   * A reauthentication while the IPN state is Running does not change the IPN state; the completion
   * is observed via the LoginFinished notification instead.
   */
  @Test
  fun reauthWhileRunningCompletesViaLoginFinished() = runTest {
    val h = harness()
    h.ipnState.value = Ipn.State.Running
    h.loginFlow.begin(notifyUser = true)
    h.loginFlow.startCompleted()

    h.browseToURLCount.value = 1
    settle()
    assertEquals(LoginPhase.AUTHENTICATING, h.loginFlow.phase.value)

    // The user completes the login in the browser; the IPN state stays Running.
    h.loginFinishedCount.value = 1
    settle()
    assertEquals(LoginPhase.IDLE, h.loginFlow.phase.value)
    assertTrue(h.errors.isEmpty())
  }

  @Test
  fun needsMachineAuthCompletionResets() = runTest {
    val h = harness()
    h.loginFlow.begin(notifyUser = true)
    h.loginFlow.startCompleted()
    h.ipnState.value = Ipn.State.NeedsMachineAuth
    settle()
    assertEquals(LoginPhase.IDLE, h.loginFlow.phase.value)
    assertTrue(h.errors.isEmpty())
  }

  /**
   * No progress at all is an offer to keep waiting or retry, not a failure: a severely degraded
   * network can legitimately take longer, so the machine stays armed in STALLED.
   */
  @Test
  fun slowLoginStallsAndOffersToRetry() = runTest {
    val h = harness()
    h.loginFlow.begin(notifyUser = true)
    h.loginFlow.startCompleted()

    // Neither an auth URL nor a completion arrives.
    advanceTimeBy(LoginFlow.AWAIT_URL_TIMEOUT_MS + 1)
    runCurrent()
    assertEquals(LoginPhase.STALLED, h.loginFlow.phase.value)
    assertEquals(listOf(LoginError.Stalled), h.errors)
  }

  @Test
  fun slowLoginStallsSilentlyForNonInteractiveLogins() = runTest {
    val h = harness()
    h.loginFlow.begin(notifyUser = false)
    h.loginFlow.startCompleted()

    advanceTimeBy(LoginFlow.AWAIT_URL_TIMEOUT_MS + 1)
    runCurrent()
    assertEquals(LoginPhase.STALLED, h.loginFlow.phase.value)
    assertTrue(h.errors.isEmpty())
  }

  /** A late auth URL still transitions a stalled login, and the login completes normally. */
  @Test
  fun authURRArrivingWhileStalledStillCompletesLogin() = runTest {
    val h = harness()
    h.loginFlow.begin(notifyUser = true)
    h.loginFlow.startCompleted()

    // The register request is very slow; the login stalls.
    advanceTimeBy(LoginFlow.AWAIT_URL_TIMEOUT_MS + 1)
    runCurrent()
    assertEquals(LoginPhase.STALLED, h.loginFlow.phase.value)

    // The auth URL finally arrives on the degraded network.
    h.browseToURLCount.value = 1
    settle()
    assertEquals(LoginPhase.AUTHENTICATING, h.loginFlow.phase.value)

    h.ipnState.value = Ipn.State.Running
    settle()
    assertEquals(LoginPhase.IDLE, h.loginFlow.phase.value)
    assertEquals(listOf(LoginError.Stalled), h.errors)
  }

  /** A retry re-requested the auth URL; the machine goes back to waiting for it. */
  @Test
  fun reRequestWhileStalledReturnsToAwaitingURL() = runTest {
    val h = harness()
    h.loginFlow.begin(notifyUser = true)
    h.loginFlow.startCompleted()
    advanceTimeBy(LoginFlow.AWAIT_URL_TIMEOUT_MS + 1)
    runCurrent()
    assertEquals(LoginPhase.STALLED, h.loginFlow.phase.value)

    // A login requested while stalled only re-requests the auth URL (IpnViewModel.login routes
    // STALLED like AWAITING_URL); the machine reflects the re-request.
    h.loginFlow.reRequested()
    assertEquals(LoginPhase.AWAITING_URL, h.loginFlow.phase.value)

    h.browseToURLCount.value = 1
    settle()
    assertEquals(LoginPhase.AUTHENTICATING, h.loginFlow.phase.value)
    assertTrue(h.errors.drop(1).isEmpty())
  }

  /** After the stall window passes with still no progress at all, the machine resets. */
  @Test
  fun stalledLoginWithoutAnyProgressEventuallyResets() = runTest {
    val h = harness()
    h.loginFlow.begin(notifyUser = true)
    h.loginFlow.startCompleted()

    advanceTimeBy(LoginFlow.AWAIT_URL_TIMEOUT_MS + 1)
    runCurrent()
    assertEquals(LoginPhase.STALLED, h.loginFlow.phase.value)

    // Still nothing at all for the whole stall window: a wedge guard, not a user-visible failure.
    advanceTimeBy(LoginFlow.STALLED_TIMEOUT_MS + 1)
    runCurrent()
    assertEquals(LoginPhase.IDLE, h.loginFlow.phase.value)
    assertEquals(listOf(LoginError.Stalled), h.errors)
  }

  @Test
  fun backendLoginErrorWithoutProgressFailsLoginWhileAwaitingURL() = runTest {
    val h = harness()
    h.loginFlow.begin(notifyUser = true)
    h.loginFlow.startCompleted()

    // The backend's health subsystem reports the login error (e.g. a canceled register request)
    // and the login makes no progress afterwards.
    h.health.value = healthWithLoginError("control: connection refused")
    settle()

    assertEquals(LoginPhase.IDLE, h.loginFlow.phase.value)
    assertEquals(listOf(LoginError.BackendError("control: connection refused")), h.errors)
  }

  @Test
  fun backendLoginErrorWithoutProgressFailsLoginAfterAuthURLDelivered() = runTest {
    val h = harness()
    h.loginFlow.begin(notifyUser = false)
    h.loginFlow.startCompleted()
    h.browseToURLCount.value = 1
    settle()
    assertEquals(LoginPhase.AUTHENTICATING, h.loginFlow.phase.value)

    // The user's authentication in the browser fails; the IPN state does not change.
    h.health.value = healthWithLoginError("auth denied")
    settle()

    assertEquals(LoginPhase.IDLE, h.loginFlow.phase.value)
    // Backend errors are surfaced even for non-interactive logins: the login chain
    // itself cannot observe them.
    assertEquals(listOf(LoginError.BackendError("auth denied")), h.errors)
  }

  /**
   * A transient login error, such as a register request canceled by a re-request tap replacing it,
   * is followed by progress (the auth URL of the replacement request) and must not fail the login;
   * see tailscale/tailscale#18304.
   */
  @Test
  fun transientLoginErrorFollowedByProgressDoesNotFailLogin() = runTest {
    val h = harness()
    h.loginFlow.begin(notifyUser = true)
    h.loginFlow.startCompleted()

    // A re-request replaced the in-flight register request; the backend reports the
    // cancellation as a login error...
    h.health.value = healthWithLoginError("fetch control key: context canceled")
    advanceTimeBy(1_000)
    runCurrent()

    // ...but the replacement request makes progress before the confirmation window closes.
    h.browseToURLCount.value = 1
    settle()
    assertEquals(LoginPhase.AUTHENTICATING, h.loginFlow.phase.value)
    assertTrue(h.errors.isEmpty())

    // The login completes normally.
    h.ipnState.value = Ipn.State.Running
    settle()
    assertEquals(LoginPhase.IDLE, h.loginFlow.phase.value)
    assertTrue(h.errors.isEmpty())
  }

  @Test
  fun staleLoginErrorFromPreviousAttemptDoesNotFailNewAttempt() = runTest {
    val h = harness()
    h.health.value = healthWithLoginError("previous failure")
    settle()

    // A new login begins while the backend still reports the previous attempt's error.
    h.loginFlow.begin(notifyUser = true)
    h.loginFlow.startCompleted()

    // The same error being re-reported must not fail the new attempt...
    h.health.value = healthWithLoginError("previous failure")
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals(LoginPhase.AWAITING_URL, h.loginFlow.phase.value)

    // ...but the auth URL still transitions normally.
    h.browseToURLCount.value = 1
    settle()
    assertEquals(LoginPhase.AUTHENTICATING, h.loginFlow.phase.value)
    assertTrue(h.errors.isEmpty())
  }

  @Test
  fun loginChainFailureResetsSoNextLoginRestarts() = runTest {
    val h = harness()
    h.loginFlow.begin(notifyUser = true)
    assertEquals(LoginPhase.STARTING, h.loginFlow.phase.value)

    // e.g. editPrefs() or start() failed in the login chain.
    h.loginFlow.failed()
    assertEquals(LoginPhase.IDLE, h.loginFlow.phase.value)

    // A new login can begin (and arms a fresh watchdog).
    h.loginFlow.begin(notifyUser = true)
    assertEquals(LoginPhase.STARTING, h.loginFlow.phase.value)
    h.loginFlow.startCompleted()
    h.browseToURLCount.value = 1
    settle()
    assertEquals(LoginPhase.AUTHENTICATING, h.loginFlow.phase.value)
  }

  @Test
  fun loginErrorStateTracksHealth() = runTest {
    val h = harness()
    assertEquals(null, h.loginFlow.loginError.value)

    h.health.value = healthWithLoginError("register request: context canceled")
    settle()
    assertEquals("register request: context canceled", h.loginFlow.loginError.value)
  }
}
