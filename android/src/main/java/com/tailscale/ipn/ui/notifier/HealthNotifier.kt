// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.UninitializedApp.Companion.notificationManager
import com.tailscale.ipn.ui.model.Health
import com.tailscale.ipn.ui.model.Health.UnhealthyState
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class HealthNotifier(
    healthStateFlow: StateFlow<Health.State?>,
    scope: CoroutineScope,
) {
  companion object {
    const val HEALTH_CHANNEL_ID = "tailscale-health"
  }

  private val TAG = "Health"
  private val ignoredWarnableCodes: Set<String> =
      setOf(
          // Ignored on Android because installing unstable takes quite some effort
          "is-using-unstable-version",

          // Ignored on Android because we already have a dedicated connected/not connected
          // notification
          "wantrunning-false")

  init {
    scope.launch {
      healthStateFlow
          .distinctUntilChanged { old, new -> old?.Warnings?.count() == new?.Warnings?.count() }
          .debounce(5000)
          .collect { health ->
            Log.d(TAG, "Health updated: ${health?.Warnings?.keys?.sorted()}")
            health?.Warnings?.let {
              notifyHealthUpdated(it.values.mapNotNull { it }.toTypedArray())
            }
          }
    }
  }

  val currentWarnings: StateFlow<Set<UnhealthyState>> = MutableStateFlow(setOf())

  private fun notifyHealthUpdated(warnings: Array<UnhealthyState>) {
    val warningsBeforeAdd = currentWarnings.value
    val currentWarnableCodes = warnings.map { it.WarnableCode }.toSet()
    val addedWarnings: MutableSet<UnhealthyState> = mutableSetOf()
    val isWarmingUp = warnings.any { it.WarnableCode == "warming-up" }

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
        Log.d(TAG, "Ignoring ${warning.WarnableCode} because of dependency")
        continue
      } else if (!isWarmingUp) {
        Log.d(TAG, "Adding health warning: ${warning.WarnableCode}")
        this.currentWarnings.set(this.currentWarnings.value + warning)
        if (warning.Severity == Health.Severity.high) {
          this.sendNotification(warning.Title, warning.Text, warning.WarnableCode)
        }
      } else {
        Log.d(TAG, "Ignoring ${warning.WarnableCode} because warming up")
      }
    }

    val warningsToDrop = warningsBeforeAdd.minus(addedWarnings)
    if (warningsToDrop.isNotEmpty()) {
      Log.d(TAG, "Dropping health warnings with codes $warningsToDrop")
      this.removeNotifications(warningsToDrop)
    }
    currentWarnings.set(this.currentWarnings.value.subtract(warningsToDrop))
  }

  private fun sendNotification(title: String, text: String, code: String) {
    Log.d(TAG, "Sending notification for $code")
    val notification =
        NotificationCompat.Builder(App.get().applicationContext, HEALTH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    if (ActivityCompat.checkSelfPermission(
        App.get().applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED) {
      Log.d(TAG, "Notification permission not granted")
      return
    }
    notificationManager.notify(code.hashCode(), notification)
  }

  private fun removeNotifications(warnings: Set<UnhealthyState>) {
    Log.d(TAG, "Removing notifications for $warnings")
    for (warning in warnings) {
      notificationManager.cancel(warning.WarnableCode.hashCode())
    }
  }
}
