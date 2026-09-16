// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Instant

class KeyExpiryNotificationWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
  override suspend fun doWork(): Result {
    val expectedExpiry =
        inputData.getString(KeyExpiryNotificationScheduler.INPUT_EXPECTED_EXPIRY)
            ?: return Result.success()

    if (!KeyExpiryNotificationScheduler.isCurrentExpiry(applicationContext, expectedExpiry)) {
      return Result.success()
    }

    val expiry =
        runCatching { Instant.parse(expectedExpiry) }.getOrNull() ?: return Result.success()

    if (!Instant.now().isBefore(expiry)) {
      return Result.success()
    }

    if (
        ActivityCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    ) {
      return Result.success()
    }

    val intent =
        Intent(applicationContext, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

    val pendingIntent =
        PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    val notification =
        NotificationCompat.Builder(applicationContext, App.KEY_EXPIRY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.key_expiry_notification_title))
            .setContentText(applicationContext.getString(R.string.key_expiry_notification_body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

    NotificationManagerCompat.from(applicationContext)
        .notify(App.KEY_EXPIRY_NOTIFICATION_ID, notification)

    return Result.success()
  }
}
