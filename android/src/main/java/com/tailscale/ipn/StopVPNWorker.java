// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.content.Context;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class StopVPNWorker extends Worker {

    public StopVPNWorker(
            Context appContext,
            WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @Override
    public Result doWork() {
        App.getApplication().setWantRunning(false);
        return Result.success();
    }
}
