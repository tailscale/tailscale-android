// Copyright (c) 2021 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import androidx.work.Worker;
import android.content.Context;
import androidx.work.WorkerParameters;
import android.net.VpnService;

public final class StartVPNWorker extends Worker {

    public StartVPNWorker(
            Context appContext,
            WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @Override public Result doWork() {
        // We will start the VPN from the background
        App app = ((App)getApplicationContext());
        app.autoConnect = true;
        // We need to make sure we prepare the VPN Service, just in case it isn't prepared.
        if (VpnService.prepare(app) == null) {
            // If null then the VPN is already prepared and/or it's just been prepared because we have permission
            app.startVPN();
            return Result.success();
        } else {
            // This VPN possibly doesn't have permission, we're in a background context, so return a failure.
            android.util.Log.e("StartVPNWorker", "Tailscale doesn't have permission to start VPN. Needs to be reauthenticated via the UI.");
            return Result.failure();
        }
    }
}
