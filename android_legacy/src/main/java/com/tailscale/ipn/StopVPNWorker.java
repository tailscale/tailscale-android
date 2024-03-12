// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import androidx.work.Worker;
import android.content.Context;
import androidx.work.WorkerParameters;

public final class StopVPNWorker extends Worker {

    public StopVPNWorker(
            Context appContext,
            WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @Override public Result doWork() {
        disconnect();
        return Result.success();
    }

    private native void disconnect();
}
