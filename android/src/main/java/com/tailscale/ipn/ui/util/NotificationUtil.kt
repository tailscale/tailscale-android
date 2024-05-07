// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tailscale.ipn.App
import com.tailscale.ipn.R

object NotificationUtil {

  const val STATUS_CHANNEL_ID = "tailscale-status"
  const val STATUS_NOTIFICATION_ID = 1
  private const val FILE_CHANNEL_ID = "tailscale-files"
  private const val FILE_NOTIFICATION_ID = 2

  fun createNotificationChannel(id: String?, name: String?, importance: Int) {
    val channel = NotificationChannel(id, name, importance)
    val nm: NotificationManagerCompat = NotificationManagerCompat.from(App.appInstance)
    nm.createNotificationChannel(channel)
  }

  fun createNotification(
      title: String?,
      message: String?,
      channel: String,
      intent: PendingIntent?
  ): Notification {
    val builder: NotificationCompat.Builder =
        NotificationCompat.Builder(App.appInstance, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    return builder.build()
  }

  fun notify(
      title: String?,
      message: String?,
      channel: String,
      intent: PendingIntent?,
      notificationID: Int
  ) {
    val nm: NotificationManagerCompat = NotificationManagerCompat.from(App.appInstance)
    if (ActivityCompat.checkSelfPermission(
        App.appInstance, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED) {
      // TODO: Consider calling
      //    ActivityCompat#requestPermissions
      // here to request the missing permissions, and then overriding
      //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
      //                                          int[] grantResults)
      // to handle the case where the user grants the permission. See the documentation
      // for ActivityCompat#requestPermissions for more details.
      return
    }
    nm.notify(notificationID, createNotification(title, message, channel, intent))
  }
}
