// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The recycle ladder, cheapest first. Each rung is a strictly more disruptive way to make the
 * tunnel pass traffic again, so the watchdog escalates only as far as it has to.
 */
internal enum class RecycleAction(val wireName: String) {
  /** Re-bind every magicsock socket to the current network. In-process, nearly free. */
  REBIND("rebind"),
  /** Force a fresh STUN/DERP discovery pass. */
  RESTUN("restun"),
  /** Force every TCP connection to be re-made. */
  BREAK_TCP_CONNS("break-tcp-conns"),
  /** The full off/on cycle: the same thing the user does by hand. */
  RESTART_VPN("restart-vpn"),
}

/** What the watchdog decided to do after one probe round. */
internal sealed interface WatchdogDecision {
  /** The tunnel is passing traffic. */
  data object Healthy : WatchdogDecision

  /** The tunnel recovered after the watchdog had escalated. */
  data object Recovered : WatchdogDecision

  /** Not enough evidence to act yet (too few failures, too few targets, or in cooldown). */
  data object Waiting : WatchdogDecision

  /** Act on the tunnel with [action]. */
  data class Act(val action: RecycleAction) : WatchdogDecision

  /** The ladder is exhausted and the tunnel is still dead. Stop and surface a warning. */
  data object GiveUp : WatchdogDecision
}

/**
 * Tunables for the watchdog. Every value names the thing it bounds.
 *
 * @param failureThreshold consecutive failed probe rounds before acting. Three rounds at the
 *   default cadence is 15 minutes of silence, which is long enough that a single dropped disco
 *   ping or a peer that is briefly asleep cannot trigger a recycle.
 * @param minProbeTargets minimum number of probe targets that must have been attempted before the
 *   watchdog will act. Two (a tailnet peer and the control plane) means a single unreachable peer
 *   is never enough on its own.
 * @param cooldownMillis minimum gap between two full VPN restarts. The engine's own recovery timers
 *   are 120 s (control-plane map watchdog) and 8 min (the "out of sync" health warning); 15 minutes
 *   sits above both so the watchdog never competes with engine self-healing, and it caps full
 *   restarts at four per hour.
 * @param maxRestarts full restarts that may fail in a row before the watchdog gives up. Three is the
 *   brief's "3 restarts in a row fail to restore traffic" limit.
 * @param probeIntervalMillis how often the watchdog samples. The failure takes 2-10 hours to appear,
 *   so a 5 minute cadence detects it within 15 minutes while costing at most 12 probes per hour.
 * @param idleThresholdMillis how long the app's UID must have moved no meaningful bytes before a
 *   probe is worth running. One full probe interval of silence is the cheapest evidence that the
 *   tunnel might be dead.
 * @param minMeaningfulBytes byte delta below which the app is considered silent. A disco ping is a
 *   few hundred bytes; 4 KiB is an order of magnitude above the probe's own footprint and far below
 *   any real user traffic in a 5 minute window, so it separates "the app is doing something" from
 *   "the app is silent".
 * @param ladder the escalation order.
 */
internal class WatchdogPolicy(
    val failureThreshold: Int = 3,
    val minProbeTargets: Int = 2,
    val cooldownMillis: Long = 15 * 60 * 1000L,
    val maxRestarts: Int = 3,
    val probeIntervalMillis: Long = 5 * 60 * 1000L,
    val idleThresholdMillis: Long = 5 * 60 * 1000L,
    val minMeaningfulBytes: Long = 4096L,
    val ladder: List<RecycleAction> =
        listOf(
            RecycleAction.REBIND,
            RecycleAction.RESTUN,
            RecycleAction.BREAK_TCP_CONNS,
            RecycleAction.RESTART_VPN,
        ),
)

/**
 * The pure decision state machine behind the watchdog. It holds no Android or coroutine state, so
 * every rule (failure counter, cooldown, escalation order, give-up) is unit-testable.
 */
internal class WatchdogCore(private val policy: WatchdogPolicy = WatchdogPolicy()) {
  private var consecutiveFailures = 0
  private var escalationIndex = 0
  private var restartAttempts = 0
  private var lastRestartAtMillis: Long? = null
  private var givenUp = false

  /** Consecutive failed probe rounds since the last success or action. */
  val failures: Int
    get() = consecutiveFailures

  /** True once the ladder has been exhausted and the tunnel is still dead. */
  val isGivenUp: Boolean
    get() = givenUp

  /**
   * Feed one probe round into the state machine.
   *
   * @param success true if the tunnel passed traffic this round.
   * @param targetsProbed how many probe targets were attempted this round.
   * @param nowMillis the current wall clock, used for the restart cooldown.
   */
  fun onProbeRound(success: Boolean, targetsProbed: Int, nowMillis: Long): WatchdogDecision {
    if (success) {
      val recovered = escalationIndex > 0 || restartAttempts > 0 || givenUp
      consecutiveFailures = 0
      escalationIndex = 0
      restartAttempts = 0
      givenUp = false
      return if (recovered) WatchdogDecision.Recovered else WatchdogDecision.Healthy
    }

    consecutiveFailures++
    if (givenUp) return WatchdogDecision.Waiting
    if (consecutiveFailures < policy.failureThreshold) return WatchdogDecision.Waiting
    if (targetsProbed < policy.minProbeTargets) return WatchdogDecision.Waiting

    val action = policy.ladder[minOf(escalationIndex, policy.ladder.lastIndex)]
    if (action == RecycleAction.RESTART_VPN) {
      val last = lastRestartAtMillis
      if (last != null && nowMillis - last < policy.cooldownMillis) {
        return WatchdogDecision.Waiting
      }
      restartAttempts++
      if (restartAttempts > policy.maxRestarts) {
        givenUp = true
        return WatchdogDecision.GiveUp
      }
      lastRestartAtMillis = nowMillis
    }

    consecutiveFailures = 0
    escalationIndex = minOf(escalationIndex + 1, policy.ladder.lastIndex)
    return WatchdogDecision.Act(action)
  }
}

/** The result of one probe round. */
internal data class ProbeOutcome(
    /** How many tailnet peers were probed. */
    val peersProbed: Int,
    /** How many of those peers answered. */
    val peersOk: Int,
    /** Whether the control plane was reachable from the app's own sockets. */
    val controlOk: Boolean,
) {
  /** Total targets attempted this round. The control plane always counts as one. */
  val targetsProbed: Int
    get() = peersProbed + 1

  /** The tunnel is alive if any probed peer answered. */
  val tunnelAlive: Boolean
    get() = peersOk > 0
}

/**
 * The watchdog coroutine. It lives inside [IPNService] because that is the only component with
 * unrestricted network access in Doze.
 *
 * It never acts unless `ipn.State == Running`: a backend that is `NeedsLogin`, `Stopped` or
 * `Starting` is not a dead tunnel, and recycling it would make the login bug worse.
 */
internal class TunnelWatchdog(
    private val scope: CoroutineScope,
    private val policy: WatchdogPolicy = WatchdogPolicy(),
    private val core: WatchdogCore = WatchdogCore(policy),
    private val isEnabled: () -> Boolean,
    private val currentState: () -> Ipn.State,
    private val bytesTransferred: () -> Long,
    private val probe: suspend () -> ProbeOutcome,
    private val act: suspend (RecycleAction) -> Unit,
    private val onGiveUp: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Durable sink for the watchdog's decisions. logcat rotates in minutes on a
     * busy device, so a decision made at 03:00 is gone by morning unless it is
     * written somewhere that survives. Defaults to a no-op so tests need no file.
     */
    private val logSink: (String) -> Unit = {},
) {
  private var job: Job? = null
  private var lastBytes: Long? = null
  private var lastTrafficAtMillis: Long = 0L
  private var lastProbeBytes: Long = 0L

  /** Logs to logcat and to the durable sink, so a decision outlives the ring buffer. */
  private fun log(message: String) {
    TSLog.d(TAG, message)
    runCatching { logSink(message) }
  }

  fun start() {
    if (job != null) return
    log("starting (interval=${policy.probeIntervalMillis}ms)")
    job = scope.launch { run() }
  }

  fun stop() {
    job?.cancel()
    job = null
    log("stopped")
  }

  private suspend fun run() {
    while (true) {
      delay(policy.probeIntervalMillis)
      try {
        tick()
      } catch (e: Exception) {
        log("tick failed: $e")
      }
    }
  }

  private suspend fun tick() {
    if (!isEnabled()) {
      log("disabled by user; skipping")
      return
    }

    val state = currentState()
    if (state != Ipn.State.Running) {
      // A backend that is not Running is not a dead tunnel. Recycling it here would fight the
      // login flow, so the watchdog stays out of the way.
      log("skip: ipn.State=$state (not Running)")
      return
    }

    val now = clock()
    val bytes = bytesTransferred()
    val prev = lastBytes
    lastBytes = bytes
    if (prev == null) {
      lastTrafficAtMillis = now
      return
    }

    // Subtract the bytes our own last probe generated so the probe cannot keep the gate open.
    val delta = (bytes - prev - lastProbeBytes).coerceAtLeast(0L)
    lastProbeBytes = 0L
    if (delta >= policy.minMeaningfulBytes) {
      lastTrafficAtMillis = now
      log("traffic flowing (${delta}B); healthy")
      handle(core.onProbeRound(success = true, targetsProbed = 0, nowMillis = now), null)
      return
    }

    val idleFor = now - lastTrafficAtMillis
    if (idleFor < policy.idleThresholdMillis) {
      log("idle ${idleFor}ms < ${policy.idleThresholdMillis}ms; waiting")
      return
    }

    val before = bytesTransferred()
    val outcome = probe()
    val after = bytesTransferred()
    lastProbeBytes = (after - before).coerceAtLeast(0L)

    val decision = core.onProbeRound(outcome.tunnelAlive, outcome.targetsProbed, now)
    handle(decision, outcome)
  }

  private suspend fun handle(decision: WatchdogDecision, outcome: ProbeOutcome?) {
    when (decision) {
      WatchdogDecision.Healthy -> log("healthy")
      WatchdogDecision.Recovered -> log("recovered; counters reset")
      WatchdogDecision.Waiting ->
          log(
              "waiting: failures=${core.failures} targets=${outcome?.targetsProbed ?: 0} " +
                  "peersOk=${outcome?.peersOk ?: 0}/${outcome?.peersProbed ?: 0} " +
                  "controlOk=${outcome?.controlOk ?: false}",
          )
      is WatchdogDecision.Act -> {
        log("acting: ${decision.action} (failures=${core.failures})")
        act(decision.action)
      }
      WatchdogDecision.GiveUp -> {
        log("giving up after ${policy.maxRestarts} restarts; tunnel still dead")
        onGiveUp()
      }
    }
  }

  companion object {
    // Not "Watchdog": Android's system_server already logs under that tag, so
    // `adb logcat -s Watchdog` would be mostly system noise.
    const val TAG = "TSWatchdog"
  }
}
