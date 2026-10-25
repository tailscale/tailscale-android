// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.util.TimeUtil
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

object KeyExpiryNotificationScheduler {
  private const val WORK_NAME = "key-expiry-notification"

  private const val PREFS_NAME = "key-expiry-notification"
  private const val PREF_CURRENT_EXPIRY = "current-expiry"

  const val INPUT_EXPECTED_EXPIRY = "expected-expiry"

  fun schedule(context: Context, node: Tailcfg.Node) {
    if (node.keyDoesNotExpire) {
      cancel(context)
      return
    }

    val expiryString = node.KeyExpiry ?: ""
    val expiry = runCatching { Instant.parse(expiryString) }.getOrNull()

    if (expiry == null) {
      cancel(context)
      return
    }

    NotificationManagerCompat.from(context).cancel(App.KEY_EXPIRY_NOTIFICATION_ID)

    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(PREF_CURRENT_EXPIRY, expiryString).apply()

    val workManager = WorkManager.getInstance(context)

    if (!Instant.now().isBefore(expiry)) {
      workManager.cancelUniqueWork(WORK_NAME)
      return
    }

    val expiryNotificationWindowMDM = MDMSettings.keyExpirationNotice.flow.value.value
    val window = expiryNotificationWindowMDM?.let { TimeUtil.duration(it) } ?: Duration.ofHours(24)

    val warningAt = expiry.minus(window)
    val delayMillis = Duration.between(Instant.now(), warningAt).toMillis().coerceAtLeast(0)

    val input = Data.Builder().putString(INPUT_EXPECTED_EXPIRY, expiryString).build()

    val request =
        OneTimeWorkRequestBuilder<KeyExpiryNotificationWorker>()
            .setInputData(input)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()

    workManager.enqueueUniqueWork(
        WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        request,
    )
  }

  fun cancel(context: Context) {
    context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .remove(PREF_CURRENT_EXPIRY)
        .apply()

    WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)

    NotificationManagerCompat.from(context).cancel(App.KEY_EXPIRY_NOTIFICATION_ID)
  }

  fun isCurrentExpiry(context: Context, expiry: String): Boolean {
    return context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(PREF_CURRENT_EXPIRY, null) == expiry
  }
}
