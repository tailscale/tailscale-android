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
import com.tailscale.ipn.InlineShareActionReceiver
import com.tailscale.ipn.R
import com.tailscale.ipn.util.InlineShare
import com.tailscale.ipn.util.PendingInlineShare
import com.tailscale.ipn.util.TSLog

object TaildropNotifier {
  private const val TAG = "TaildropNotifier"
  // Notification extras cross a binder transaction; keep the preview well clear of its limit.
  private const val PREVIEW_CHARS = 120

  fun cancel(context: Context, id: String) {
    NotificationManagerCompat.from(context).cancel(id.hashCode())
  }

  fun notify(context: Context, pending: PendingInlineShare) {
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED) {
      TSLog.d(TAG, "POST_NOTIFICATIONS not granted; skipping inline share notification")
      return
    }

    val title: String
    val body: String
    val subtitle: String
    when (pending.kind) {
      InlineShare.Kind.URL -> {
        title = context.getString(R.string.taildrop_link_received)
        body = pending.content
        subtitle = context.getString(R.string.taildrop_tap_to_open)
      }
      InlineShare.Kind.TEXT -> {
        title = context.getString(R.string.taildrop_text_received)
        body = pending.content.take(120).replace("\n", " ")
        subtitle = context.getString(R.string.taildrop_tap_to_copy)
      }
    }

    val tapIntent =
        Intent(context, InlineShareActionReceiver::class.java).apply {
          action = InlineShareActionReceiver.ACTION_CONSUME
          putExtra(InlineShareActionReceiver.EXTRA_ID, pending.id)
        }

    val pendingIntent =
        PendingIntent.getBroadcast(
            context,
            pending.id.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    // Without a delete intent a swipe-away is invisible to us, leaving the inbox entry
    // (and, for URLs, the file) behind.
    val dismissIntent =
        Intent(context, InlineShareActionReceiver::class.java).apply {
          action = InlineShareActionReceiver.ACTION_DISMISS
          putExtra(InlineShareActionReceiver.EXTRA_ID, pending.id)
        }

    val dismissPendingIntent =
        PendingIntent.getBroadcast(
            context,
            "dismiss-${pending.id}".hashCode(),
            dismissIntent,
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
            .setDeleteIntent(dismissPendingIntent)
            .build()

    NotificationManagerCompat.from(context).notify(pending.id.hashCode(), notification)
  }
}
