// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class StartVPNWorker extends Worker {

    public StartVPNWorker(
            Context appContext,
            WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @Override
    public Result doWork() {
        App app = ((App) getApplicationContext());
        // We need to make sure we prepare the VPN Service, just in case it isn't prepared.
        Intent intent = VpnService.prepare(app);
        if (intent == null) {
            // If null then the VPN is already prepared and/or it's just been prepared because we have permission
            app.startVPN();
            return Result.success();
        } else {
            // This VPN possibly doesn't have permission, we need to display a notification which when clicked launches the intent provided.
            android.util.Log.e("StartVPNWorker", "Tailscale doesn't have permission from the system to start VPN. Launching the intent provided.");

            // Send notification
            NotificationManager notificationManager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            String channelId = "start_vpn_channel";

            // Use createNotificationChannel method from App.java
            app.createNotificationChannel(channelId, "Start VPN Channel", NotificationManager.IMPORTANCE_DEFAULT);

            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int pendingIntentFlags = PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pendingIntent = PendingIntent.getActivity(app, 0, intent, pendingIntentFlags);

            Notification notification = new Notification.Builder(app, channelId)
                    .setContentTitle("Tailscale Connection Failed")
                    .setContentText("Tap here to renew permission.")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();

            notificationManager.notify(1, notification);

            return Result.failure();
        }
    }
}
