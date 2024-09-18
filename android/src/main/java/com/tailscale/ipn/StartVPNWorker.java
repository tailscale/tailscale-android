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

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.tailscale.ipn.util.TSLog;

/**
 * A worker that exists to support IPNReceiver.
 */
public final class StartVPNWorker extends Worker {

    public StartVPNWorker(Context appContext, WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        UninitializedApp app = UninitializedApp.get();
        boolean ableToStartVPN = app.isAbleToStartVPN();
        if (ableToStartVPN) {
            if (VpnService.prepare(app) == null) {
                // We're ready and have permissions, start the VPN
                app.startVPN();
                return Result.success();
            }
        }

        // We aren't ready to start the VPN or don't have permission, open the Tailscale app.
        TSLog.e("StartVPNWorker", "Tailscale isn't ready to start the VPN, notify the user.");

        // Send notification
        NotificationManager notificationManager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        String channelId = "start_vpn_channel";

        // Use createNotificationChannel method from App.java
        app.createNotificationChannel(channelId, getApplicationContext().getString(R.string.vpn_start), getApplicationContext().getString(R.string.notifications_delivered_when_user_interaction_is_required_to_establish_the_vpn_tunnel), NotificationManager.IMPORTANCE_HIGH);

        // Use prepareIntent if available.
        Intent intent = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        assert intent != null;
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int pendingIntentFlags = PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pendingIntent = PendingIntent.getActivity(app, 0, intent, pendingIntentFlags);

        Notification notification = new Notification.Builder(app, channelId).setContentTitle(app.getString(R.string.title_connection_failed)).setContentText(app.getString(R.string.body_open_tailscale)).setSmallIcon(R.drawable.ic_notification).setContentIntent(pendingIntent).setAutoCancel(true).build();

        notificationManager.notify(1, notification);

        return Result.failure();
    }
}
