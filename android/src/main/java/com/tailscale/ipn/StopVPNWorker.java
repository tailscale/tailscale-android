// Copyright (c) 2023 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

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
