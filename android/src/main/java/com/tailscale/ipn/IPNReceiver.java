// Copyright (c) 2023 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.work.Data;
import androidx.work.WorkManager;
import androidx.work.OneTimeWorkRequest;

public class IPNReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        WorkManager workManager = WorkManager.getInstance(context);

        // On the relevant action, start the relevant worker, which can stay active for longer than this receiver can.
        if (intent.getAction() == "com.tailscale.ipn.CONNECT_VPN") {
            workManager.enqueue(new OneTimeWorkRequest.Builder(StartVPNWorker.class).build());
        } else if (intent.getAction() == "com.tailscale.ipn.DISCONNECT_VPN") {
            workManager.enqueue(new OneTimeWorkRequest.Builder(StopVPNWorker.class).build());
        }
        else if (intent.getAction() == "com.tailscale.ipn.USE_EXIT_NODE") {
            String exitNode = intent.getStringExtra("exitNode");
            boolean allowLanAccess = intent.getBooleanExtra("allowLanAccess", false);
            if (exitNode == null) {
                exitNode = "";
            }
            Data.Builder workData = new Data.Builder();
            workData.putString(UseExitNodeWorker.EXIT_NODE, exitNode);
            workData.putBoolean(UseExitNodeWorker.ALLOW_LAN_ACCESS, allowLanAccess);
            workManager.enqueue(new OneTimeWorkRequest.Builder(UseExitNodeWorker.class).setInputData(workData.build()).build());
        }
    }
}
