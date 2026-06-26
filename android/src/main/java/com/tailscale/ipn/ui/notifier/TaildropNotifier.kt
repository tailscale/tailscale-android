// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.TdPayloadActionReceiver
import com.tailscale.ipn.util.PendingTdPayload
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.util.TdPayload

// Posts a system notification for an incoming TdPayload on the Taildrop
// file channel; tap fires TdPayloadActionReceiver.
object TaildropNotifier {
  private const val TAG = "TaildropNotifier"

  fun cancel(context: Context, id: String) {
    NotificationManagerCompat.from(context).cancel(id.hashCode())
  }

  fun notify(context: Context, pending: PendingTdPayload) {
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED) {
      TSLog.d(TAG, "POST_NOTIFICATIONS not granted; skipping tdpayload notification")
      return
    }

    val title: String
    val body: String
    val subtitle: String
    when (pending.kind) {
      TdPayload.Kind.URL -> {
        title = context.getString(R.string.taildrop_link_received)
        body = pending.title ?: pending.content
        subtitle = context.getString(R.string.taildrop_tap_to_open)
      }
      TdPayload.Kind.TEXT -> {
        title = context.getString(R.string.taildrop_text_received)
        body = pending.content.take(120).replace("\n", " ")
        subtitle = context.getString(R.string.taildrop_tap_to_copy)
      }
    }

    val tapIntent =
        Intent(context, TdPayloadActionReceiver::class.java).apply {
          action = TdPayloadActionReceiver.ACTION_CONSUME
          putExtra(TdPayloadActionReceiver.EXTRA_KIND, pending.kind.name.lowercase())
          putExtra(TdPayloadActionReceiver.EXTRA_CONTENT, pending.content)
          putExtra(TdPayloadActionReceiver.EXTRA_ID, pending.id)
        }

    val pendingIntent =
        PendingIntent.getBroadcast(
            context,
            pending.id.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val notification =
        NotificationCompat.Builder(context, App.FILE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()

    NotificationManagerCompat.from(context).notify(pending.id.hashCode(), notification)
  }
}
