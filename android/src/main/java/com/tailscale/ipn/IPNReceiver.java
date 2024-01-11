// Copyright (c) 2023 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.work.WorkManager;
import androidx.work.OneTimeWorkRequest;
import android.util.Log;

public class IPNReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        WorkManager workManager = WorkManager.getInstance(context);

        // On the relevant action, start the relevant worker, which can stay active for longer than this receiver can.
        if (intent.getAction() == "com.tailscale.ipn.CONNECT_VPN") {
            workManager.enqueue(new OneTimeWorkRequest.Builder(StartVPNWorker.class).build());
        } else if (intent.getAction() == "com.tailscale.ipn.DISCONNECT_VPN") {
            workManager.enqueue(new OneTimeWorkRequest.Builder(StopVPNWorker.class).build());
        } else if (intent.getAction() == "com.tailscale.ipn.INIT_WITH_AUTHKEY") {
            String key = intent.getStringExtra("authkey");
            IPNService.setAuthKeyForNextConnect(key);
        }
    }
}
