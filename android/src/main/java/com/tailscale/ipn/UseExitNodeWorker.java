// Copyright (c) 2023 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class UseExitNodeWorker extends Worker {
    public static final String EXIT_NODE = "USE_EXIT_NODE";
    public static final String ALLOW_LAN_ACCESS = "ALLOW_LAN_ACCESS";

    public UseExitNodeWorker(
            Context appContext,
            WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        String exitNode = getInputData().getString(EXIT_NODE);
        if (exitNode == null) {
            exitNode = "";
        }
        boolean allowLanAccess = getInputData().getBoolean(ALLOW_LAN_ACCESS, false);

        useExitNode(exitNode, allowLanAccess);

        return Result.success();
    }

    public native void useExitNode(String exitNode, boolean allowLanAccess);
}
