// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.work.Data;

import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.Objects;

/**
 * IPNReceiver allows external applications to start the VPN.
 */
public class IPNReceiver extends BroadcastReceiver {

    public static final String INTENT_CONNECT_VPN = "com.tailscale.ipn.CONNECT_VPN";
    public static final String INTENT_DISCONNECT_VPN = "com.tailscale.ipn.DISCONNECT_VPN";

    private static final String INTENT_USE_EXIT_NODE = "com.tailscale.ipn.USE_EXIT_NODE";

    @Override
    public void onReceive(Context context, Intent intent) {
        WorkManager workManager = WorkManager.getInstance(context);

        // On the relevant action, start the relevant worker, which can stay active for longer than this receiver can.
        if (Objects.equals(intent.getAction(), INTENT_CONNECT_VPN)) {
            workManager.enqueue(new OneTimeWorkRequest.Builder(StartVPNWorker.class).build());
        } else if (Objects.equals(intent.getAction(), INTENT_DISCONNECT_VPN)) {
            workManager.enqueue(new OneTimeWorkRequest.Builder(StopVPNWorker.class).build());
        }
        else if (Objects.equals(intent.getAction(), INTENT_USE_EXIT_NODE)) {
            String exitNode = intent.getStringExtra("exitNode");
            boolean allowLanAccess = intent.getBooleanExtra("allowLanAccess", false);
            Data.Builder workData = new Data.Builder();
            workData.putString(UseExitNodeWorker.EXIT_NODE_NAME, exitNode);
            workData.putBoolean(UseExitNodeWorker.ALLOW_LAN_ACCESS, allowLanAccess);
            workManager.enqueue(new OneTimeWorkRequest.Builder(UseExitNodeWorker.class).setInputData(workData.build()).build());
        }
    }
}
