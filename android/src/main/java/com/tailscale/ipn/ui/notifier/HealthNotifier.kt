// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.UninitializedApp.Companion.notificationManager
import com.tailscale.ipn.ui.model.Health
import com.tailscale.ipn.ui.model.Health.UnhealthyState
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HealthNotifier(
    healthStateFlow: StateFlow<Health.State?>,
    ipnStateFlow: StateFlow<Ipn.State>,
    scope: CoroutineScope,
) {
  companion object {
    const val HEALTH_CHANNEL_ID = "tailscale-health"

    // Keep in sync with routeLimitWarnable in libtailscale/net.go.
    const val ROUTE_LIMIT_CODE = "android-route-limit"
  }

  private val TAG = "health"
  // These warnings can prevent connection. Show them while disconnected or warming up,
  // until the backend clears them after the problem is resolved.
  private val persistentWarnableCodes: Set<String> = setOf(ROUTE_LIMIT_CODE)

  private val ignoredWarnableCodes: Set<String> =
      setOf(
          // Ignored on Android because installing unstable takes quite some effort
          "is-using-unstable-version",

          // Ignored on Android because we already have a dedicated connected/not connected
          // notification
          "wantrunning-false",
      )

  // These must be initialized before the init block below, which launches a coroutine that can
  // immediately call notifyHealthUpdated() (reading currentWarnings) on a background dispatcher. If
  // the collector observes the initial non-Running ipn state before these property initializers
  // run, it would read a null StateFlow and crash with an NPE (see startup init-order race).
  val currentWarnings: StateFlow<Set<UnhealthyState>> = MutableStateFlow(setOf())
  val currentIcon: StateFlow<Int?> = MutableStateFlow(null)

  init {
    // This roughly matches the iOS/macOS implementation in terms of debouncing, and ignoring
    // health warnings in various states.
    scope.launch {
      ipnStateFlow
          .flatMapLatest { ipnState ->
            if (ipnState == Ipn.State.Running) {
              healthStateFlow
                  .distinctUntilChanged { old, new ->
                    old?.Warnings.orEmpty() == new?.Warnings.orEmpty()
                  }
                  .debounce(3000)
            } else {
              // Keep listening for persistent warnings while stopped, including failed retries.
              healthStateFlow.map { health ->
                health?.copy(
                    Warnings = health.Warnings?.filterKeys { it in persistentWarnableCodes }
                )
              }
            }
          }
          .collect { health ->
            TSLog.d(TAG, "Health updated: ${health?.Warnings?.keys?.sorted()}")
            notifyHealthUpdated(health?.Warnings?.values.orEmpty().filterNotNull().toTypedArray())
          }
    }
  }

  private fun notifyHealthUpdated(warnings: Array<UnhealthyState>) {
    val warningsBeforeAdd = currentWarnings.value
    val currentWarnableCodes = warnings.map { it.WarnableCode }.toSet()
    val addedWarnings: MutableSet<UnhealthyState> = mutableSetOf()
    val removedByNewDependency: MutableSet<UnhealthyState> = mutableSetOf()
    val isWarmingUp = warnings.any { it.WarnableCode == "warming-up" }

    /**
     * dropDependenciesForAddedWarning checks if there is any warning in `warningsBeforeAdd` that
     * needs to be removed because the new warning `w` is listed as a dependency of a warning
     * already in `warningsBeforeAdd`, and removes it.
     */
    fun dropDependenciesForAddedWarning(w: UnhealthyState) {
      for (warning in warningsBeforeAdd) {
        warning.DependsOn?.let {
          if (it.contains(w.WarnableCode)) {
            removedByNewDependency.add(warning)
          }
        }
      }
    }

    for (warning in warnings) {
      if (ignoredWarnableCodes.contains(warning.WarnableCode)) {
        continue
      }

      addedWarnings.add(warning)

      if (this.currentWarnings.value.contains(warning)) {
        // Already notified, skip
        continue
      } else if (warning.hiddenByDependencies(currentWarnableCodes)) {
        // Ignore this warning because a dependency is also unhealthy
        TSLog.d(TAG, "Ignoring ${warning.WarnableCode} because of dependency")
        continue
      } else if (!isWarmingUp || warning.WarnableCode in persistentWarnableCodes) {
        TSLog.d(TAG, "Adding health warning: ${warning.WarnableCode}")
        this.currentWarnings.set(this.currentWarnings.value + warning)
        dropDependenciesForAddedWarning(warning)
        if (warning.Severity == Health.Severity.high) {
          this.sendNotification(warning.Title, warning.Text, warning.WarnableCode)
        }
      } else {
        TSLog.d(TAG, "Ignoring ${warning.WarnableCode} because warming up")
      }
    }

    val warningsToDrop = warningsBeforeAdd.minus(addedWarnings).union(removedByNewDependency)
    if (warningsToDrop.isNotEmpty()) {
      TSLog.d(TAG, "Dropping health warnings with codes $warningsToDrop")
      this.removeNotifications(warningsToDrop)
    }
    currentWarnings.set(this.currentWarnings.value.subtract(warningsToDrop))
    this.updateIcon()
  }

  /**
   * Sets the icon displayed to represent the overall health state.
   * - If there are any high severity warnings, or warnings that affect internet connectivity, a
   *   warning icon is displayed.
   * - If there are any other kind of warnings, an info icon is displayed.
   * - If there are no warnings at all, no icon is set.
   */
  private fun updateIcon() {
    if (currentWarnings.value.isEmpty()) {
      this.currentIcon.set(null)
      return
    }
    if (
        currentWarnings.value.any {
          (it.Severity == Health.Severity.high || it.ImpactsConnectivity == true)
        }
    ) {
      this.currentIcon.set(R.drawable.warning_rounded)
    } else {
      this.currentIcon.set(R.drawable.info)
    }
  }

  private fun sendNotification(title: String, text: String, code: String) {
    TSLog.d(TAG, "Sending notification for $code")
    val notification =
        NotificationCompat.Builder(App.get().applicationContext, HEALTH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    if (
        ActivityCompat.checkSelfPermission(
            App.get().applicationContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    ) {
      TSLog.d(TAG, "Notification permission not granted")
      return
    }
    notificationManager.notify(code.hashCode(), notification)
  }

  private fun removeNotifications(warnings: Set<UnhealthyState>) {
    TSLog.d(TAG, "Removing notifications for $warnings")
    for (warning in warnings) {
      notificationManager.cancel(warning.WarnableCode.hashCode())
    }
  }
}
