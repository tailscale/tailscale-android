// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.tailscale.ipn.UninitializedApp.Companion.STATUS_CHANNEL_ID

internal fun workerForegroundInfo(): ForegroundInfo {
  val app = UninitializedApp.get()
  val notification =
      NotificationCompat.Builder(app, STATUS_CHANNEL_ID)
          .setSmallIcon(R.drawable.ic_notification)
          .setContentTitle(app.getString(R.string.app_name))
          .setPriority(NotificationCompat.PRIORITY_LOW)
          .setOngoing(true)
          .build()

  return ForegroundInfo(
      WORKER_FOREGROUND_NOTIFICATION_ID,
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
  )
}

private const val WORKER_FOREGROUND_NOTIFICATION_ID = 1001
