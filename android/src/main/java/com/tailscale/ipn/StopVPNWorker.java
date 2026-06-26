// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import static com.tailscale.ipn.UninitializedApp.STATUS_NOTIFICATION_ID;

import android.app.Application;
import android.app.Notification;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * A worker that exists to support IPNReceiver.
 */
public final class StopVPNWorker extends Worker {

    public StopVPNWorker(
            Context appContext,
            WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        UninitializedApp.get().stopVPN();
        return Result.success();
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        // notification just so that there is no exception on android 11 and older (api 30 and older)
        // it will be only briefly visible in the real world because the intent finishes almost instantly
        // https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#backwards-compat
        Application app = UninitializedApp.get();
        Notification notification = new NotificationCompat.Builder(app, UninitializedApp.STATUS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(app.getString(R.string.stopping_notification))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();

        return new ForegroundInfo(STATUS_NOTIFICATION_ID, notification);
    }
}
