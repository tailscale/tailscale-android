// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import com.tailscale.ipn.ui.model.Health
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phases of a login, shared by all view models since login can be triggered from several
 * screens (the main connect view, the key expiry banner, the user switcher's "Reauthenticate").
 */
enum class LoginPhase {
  /** No login in flight. */
  IDLE,

  /**
   * editPrefs()/start() in flight. A login requested now is ignored: a start() here would tear down
   * the control client and cancel the in-flight register request.
   */
  STARTING,

  /**
   * start() completed; waiting for the backend to deliver an auth URL. A login requested now only
   * re-requests the auth URL via startLoginInteractive().
   */
  AWAITING_URL,

  /**
   * The auth URL was delivered and the user is (presumably) signing in in the browser. A login
   * requested now only re-requests the auth URL via startLoginInteractive().
   */
  AUTHENTICATING,

  /**
   * No progress for [AWAIT_URL_TIMEOUT_MS]: the network is likely severely degraded and the login
   * (e.g. a register request) may still be slowly in flight. This is an offer to keep waiting or to
   * retry, not a failure: the machine stays armed and keeps watching, and a login requested now
   * only re-requests the auth URL via startLoginInteractive() like in [AWAITING_URL], rather than
   * restarting the login chain (which would cancel the in-flight request).
   */
  STALLED,
}

/**
 * State machine that tracks an interactive login across the app and communicates its progress.
 *
 * All [IpnViewModel]s share one instance (logins are started from several screens), and the UI
 * observes [phase] to disable login controls and to show what the login is currently waiting for,
 * so that a login in progress is visible rather than a button that appears to do nothing
 * (tailscale/tailscale#18304).
 *
 * Besides the transitions driven by the login chain itself (see [IpnViewModel.login]), a watchdog
 * observes the IPN bus and drives the machine on:
 * - the backend delivering an auth URL (-> [LoginPhase.AUTHENTICATING]),
 * - the login completing (a LoginFinished notification, or the IPN state leaving the login states;
 *   a reauthentication while Running does not change the IPN state), and
 * - the backend's health subsystem reporting a login error via the login-state warnable
 *   (tailscale.com/health LoginStateWarnable). A login error only fails the login if no progress
 *   (an auth URL or a completion) follows within [BACKEND_ERROR_CONFIRM_MS]: transient errors, such
 *   as a register request canceled by a re-request tap replacing it, are followed by progress and
 *   must not fail the login.
 *
 * The watchdog also arms a stall threshold: a login that produces no signal at all (neither an auth
 * URL, nor a completion, nor a health error) within [AWAIT_URL_TIMEOUT_MS] transitions to
 * [LoginPhase.STALLED]. That is an offer to keep waiting or to retry, not a failure: a severely
 * degraded network can legitimately take longer (a register request still in flight), so the
 * machine stays armed and keeps watching within [STALLED_TIMEOUT_MS] rather than resetting, and a
 * login requested while stalled only re-requests the auth URL instead of restarting the login chain
 * (which would cancel the in-flight request).
 *
 * Watchdog-driven events are reported via [onError]: backend login errors are always reported (the
 * login chain itself cannot observe them); stall offers are reported only for logins begun with
 * [begin]'s [notifyUser], i.e. plain interactive logins, whose screens do not report progress
 * themselves (auth-key logins' screens do).
 */
class LoginFlow(
    private val scope: CoroutineScope,
    private val ipnState: StateFlow<Ipn.State>,
    private val browseToURLCount: StateFlow<Long>,
    private val loginFinishedCount: StateFlow<Long>,
    private val health: StateFlow<Health.State?>,
    private val onError: (LoginError) -> Unit = {},
) {
  companion object {
    /**
     * How long to wait for a progress signal (an auth URL, a completion, or a health error) before
     * offering to keep waiting or retry. Not a failure deadline: a severely degraded network can
     * take longer.
     */
    const val AWAIT_URL_TIMEOUT_MS = 30_000L

    /**
     * How long a stalled login keeps being watched (from the stall) before the machine is reset so
     * that a new request can restart the login chain. Only a wedge guard: no user-visible failure
     * is reported for it.
     */
    const val STALLED_TIMEOUT_MS = 10 * 60_000L

    /** How long a login whose auth URL was delivered is still considered in progress. */
    const val AUTHENTICATING_TIMEOUT_MS = 10 * 60_000L

    /**
     * How long to wait for progress (an auth URL or a completion) after the backend reports a login
     * error before failing the login. Transient login errors (e.g. a register request canceled by a
     * re-request tap replacing it) are followed by progress within this window.
     */
    const val BACKEND_ERROR_CONFIRM_MS = 5_000L

    private const val TAG = "LoginFlow"
  }

  /** A failure reported to [onError]. */
  sealed interface LoginError {
    /** The backend reported a login error; [text] is the backend's error message. */
    data class BackendError(val text: String) : LoginError

    /**
     * No progress signal at all within [AWAIT_URL_TIMEOUT_MS]: the login may still complete on its
     * own (a severely degraded network), and the user is offered to keep waiting or to retry.
     */
    data object Stalled : LoginError
  }

  private val _phase = MutableStateFlow(LoginPhase.IDLE)
  val phase: StateFlow<LoginPhase> = _phase

  /** The login error text last reported by the backend's health subsystem, if any. */
  private val _loginError = MutableStateFlow<String?>(null)
  val loginError: StateFlow<String?> = _loginError

  private var watchdog: Job? = null

  /** Whether a watchdog failure of the login currently in progress is surfaced to the user. */
  private var notifyUser = false

  /** Tracks the backend's login error, so the UI can explain why a login is not progressing. */
  init {
    scope.launch { health.collect { state -> _loginError.value = state.loginError() } }
  }

  /**
   * Starts tracking a new login. Arms the watchdog with the values observed now, so that a change
   * which happens before the watchdog subscribes is not missed, and so that a login error already
   * known from a previous attempt does not fail the new attempt before it had a chance.
   *
   * @param notifyUser whether a watchdog event (no progress within [AWAIT_URL_TIMEOUT_MS]) of this
   *   login is surfaced via [onError].
   */
  @Synchronized
  fun begin(notifyUser: Boolean) {
    TSLog.d(TAG, "login: phase -> STARTING")
    this.notifyUser = notifyUser
    _phase.value = LoginPhase.STARTING
    _loginError.value = health.value.loginError()
    armWatchdog()
  }

  /**
   * The login chain's start() completed; it replaced the control client. From here on, a repeated
   * login request only needs startLoginInteractive() (see [IpnViewModel.login]).
   */
  @Synchronized
  fun startCompleted() {
    if (_phase.value == LoginPhase.STARTING) {
      TSLog.d(TAG, "login: phase -> AWAITING_URL")
      _phase.value = LoginPhase.AWAITING_URL
    }
  }

  /**
   * The login chain failed (editPrefs/start/startLoginInteractive returned an error). Resets the
   * machine so that the next request restarts the flow.
   */
  @Synchronized
  fun failed() {
    reset("login chain failed")
  }

  /**
   * A repeated login request re-requested the auth URL via startLoginInteractive() instead of
   * restarting the login chain; the machine keeps watching with its existing baselines.
   */
  @Synchronized
  fun reRequested() {
    if (_phase.value == LoginPhase.STALLED) {
      TSLog.d(TAG, "login: phase -> AWAITING_URL (auth URL re-requested)")
      _phase.value = LoginPhase.AWAITING_URL
    }
  }

  /** Resets the machine to [LoginPhase.IDLE] and stops the watchdog. */
  @Synchronized
  private fun reset(reason: String) {
    watchdog?.cancel()
    watchdog = null
    if (_phase.value != LoginPhase.IDLE) {
      TSLog.d(TAG, "login: phase -> IDLE ($reason)")
    }
    _phase.value = LoginPhase.IDLE
  }

  @Synchronized
  private fun armWatchdog() {
    watchdog?.cancel()
    val startCount = browseToURLCount.value
    val startState = ipnState.value
    val baselineError = _loginError.value
    watchdog = scope.launch {
      // Stage 1: wait for the auth URL, a login error, or the login to complete.
      // A login error is only a failure if no progress follows it within
      // BACKEND_ERROR_CONFIRM_MS: transient errors, such as a register request
      // canceled by a re-request tap replacing it, are followed by progress.
      val urlOrFinished =
          withTimeoutOrNull(AWAIT_URL_TIMEOUT_MS) {
            confirmedOrProgress(
                awaitAuthURL(startCount, startState, baselineError),
                startCount,
                startState,
            )
          }
      when (urlOrFinished) {
        // No signal at all within AWAIT_URL_TIMEOUT_MS: the network is likely
        // severely degraded and the login may still be slowly in flight. Offer to
        // keep waiting or to retry; do not reset, and keep watching, so that a late
        // auth URL or completion is still observed. A retry from STALLED only
        // re-requests the auth URL (see [reRequested] and [IpnViewModel.login]);
        // restarting the login chain would cancel the in-flight request.
        null -> {
          TSLog.w(
              TAG,
              "login: no progress after ${AWAIT_URL_TIMEOUT_MS}ms; offering to keep waiting or retry",
          )
          _phase.value = LoginPhase.STALLED
          if (notifyUser) {
            onError(LoginError.Stalled)
          }
          val late =
              withTimeoutOrNull(STALLED_TIMEOUT_MS) {
                confirmedOrProgress(
                    awaitAuthURL(startCount, startState, _loginError.value),
                    startCount,
                    startState,
                )
              }
          when (late) {
            // Still nothing at all: give up watching (the login chain is not
            // making progress), so a new request can restart the login chain.
            null -> reset("timed out waiting for progress")
            is Finished -> {
              TSLog.d(
                  TAG,
                  "login: complete before auth URL (state $startState -> ${ipnState.value})",
              )
              reset("login complete")
            }
            is LoginError.BackendError -> {
              TSLog.w(TAG, "login: backend login error persisted: ${late.text}")
              reset("backend login error")
              onError(late)
            }
            URLDelivered -> completeAfterAuthURL()
          }
        }
        is Finished -> {
          TSLog.d(TAG, "login: complete before auth URL (state $startState -> ${ipnState.value})")
          reset("login complete")
        }
        is LoginError.BackendError -> {
          TSLog.w(TAG, "login: backend login error persisted: ${urlOrFinished.text}")
          reset("backend login error")
          // The login chain itself cannot observe backend errors, so they are always
          // surfaced.
          onError(urlOrFinished)
        }
        // The auth URL was delivered; the user is signing in in the browser. Keep
        // treating repeated taps as "show the URL again" until the user finishes (or
        // abandons) the login, but keep watching for errors and completion.
        URLDelivered -> completeAfterAuthURL()
      }
    }
  }

  /**
   * A backend login error only ends a wait if no progress (an auth URL, or the login completing)
   * follows it within [BACKEND_ERROR_CONFIRM_MS]; otherwise the progress signal is returned
   * instead, as though the transient error had not happened.
   */
  private suspend fun confirmedOrProgress(signal: Any?, startCount: Long, startState: Ipn.State) =
      if (signal is LoginError.BackendError) {
        val progress = awaitProgress(startCount, startState)
        if (progress == null) signal else progress
      } else {
        signal
      }

  /**
   * Waits for the user to finish signing in in the browser: a LoginFinished notification (a
   * reauthentication while Running does not change the IPN state), or the IPN state leaving the
   * login states; a backend login error fails only if no progress follows it. Resets the machine
   * when the wait ends.
   */
  private suspend fun completeAfterAuthURL() {
    _phase.value = LoginPhase.AUTHENTICATING
    TSLog.d(TAG, "login: phase -> AUTHENTICATING")
    val urlState = ipnState.value
    val startLoginFinishedCount = loginFinishedCount.value
    // A login error that was already reported when the URL arrived (e.g. the transient
    // cancellation of the request this URL replaced) is superseded by the URL delivery; only
    // errors reported after it can fail the login.
    val urlBaselineError = _loginError.value
    val finished =
        withTimeoutOrNull(AUTHENTICATING_TIMEOUT_MS) {
          val signal = awaitLoginFinished(urlState, startLoginFinishedCount, urlBaselineError)
          if (signal is LoginError.BackendError) {
            // Only fail the login if no progress (here: the login finishing, or the IPN
            // state leaving the login states) follows the error.
            val progress =
                withTimeoutOrNull(BACKEND_ERROR_CONFIRM_MS) {
                  awaitLoginProgress(urlState, startLoginFinishedCount)
                }
            if (progress == null) signal else Finished
          } else {
            signal
          }
        }
    when (finished) {
      null -> reset("timed out waiting for login completion")
      is LoginError.BackendError -> {
        TSLog.w(TAG, "login: backend login error persisted: ${finished.text}")
        reset("backend login error")
        // An error this late in the login was never reported by the login chain itself, so
        // it is surfaced even without [notifyUser].
        onError(finished)
      }
      else -> {
        TSLog.d(TAG, "login: finished after auth (state $urlState -> ${ipnState.value})")
        reset("login finished after auth")
      }
    }
  }

  /**
   * Returns the first signal that ends the wait for the auth URL: the backend (re)delivering an
   * auth URL, the IPN state reaching a state only a completed login reaches, or the backend
   * reporting a login error that differs from [baselineError] (a previous attempt's error).
   */
  private suspend fun awaitAuthURL(
      startCount: Long,
      startState: Ipn.State,
      baselineError: String?,
  ) =
      merge(
              browseToURLCount.filter { it != startCount }.map { URLDelivered },
              ipnState
                  .filter {
                    it != startState &&
                        (it == Ipn.State.Running || it == Ipn.State.NeedsMachineAuth)
                  }
                  .map { Finished },
              health
                  .mapNotNull { it.loginError() }
                  .filter { it != baselineError }
                  .map { LoginError.BackendError(it) },
          )
          .first()

  /**
   * Returns the first signal that ends the wait for the user to finish signing in: a LoginFinished
   * notification (a reauthentication while Running does not change the IPN state), the IPN state
   * leaving the login states (NeedsLogin means the login was abandoned or reset), or the backend
   * reporting a login error that differs from [baselineError]. A repeated delivery of the same auth
   * URL (e.g. the user tapped "Log in" again) is not a completion signal.
   */
  private suspend fun awaitLoginFinished(
      urlState: Ipn.State,
      startLoginFinishedCount: Long,
      baselineError: String?,
  ) =
      merge(
              loginFinishedCount.filter { it != startLoginFinishedCount }.map { Finished },
              ipnState.filter { it != urlState && it != Ipn.State.NeedsLogin }.map { Finished },
              health
                  .mapNotNull { it.loginError() }
                  .filter { it != baselineError }
                  .map { LoginError.BackendError(it) },
          )
          .first()

  /**
   * Waits for the login to make progress after a backend login error was reported while the user is
   * signing in: the login finishing, or the IPN state leaving the login states.
   */
  private suspend fun awaitLoginProgress(urlState: Ipn.State, startLoginFinishedCount: Long) =
      merge(
              loginFinishedCount.filter { it != startLoginFinishedCount }.map { Finished },
              ipnState.filter { it != urlState && it != Ipn.State.NeedsLogin }.map { Finished },
          )
          .first()

  /**
   * Waits for the login to make progress (the backend delivering an auth URL, or the login
   * completing) within [BACKEND_ERROR_CONFIRM_MS]. Returns the progress signal, or null if none
   * followed.
   */
  private suspend fun awaitProgress(startCount: Long, startState: Ipn.State) =
      withTimeoutOrNull(BACKEND_ERROR_CONFIRM_MS) {
        merge(
                browseToURLCount.filter { it != startCount }.map { URLDelivered },
                ipnState
                    .filter {
                      it != startState &&
                          (it == Ipn.State.Running || it == Ipn.State.NeedsMachineAuth)
                    }
                    .map { Finished },
            )
            .first()
      }

  /** The login error reported by the backend's health subsystem, if any. */
  private fun Health.State?.loginError(): String? = this?.loginStateError

  private object URLDelivered

  private object Finished
}
