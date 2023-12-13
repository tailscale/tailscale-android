// Copyright (c) 2023 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.work.WorkManager;
import androidx.work.OneTimeWorkRequest;

public class IPNReceiver extends BroadcastReceiver {

    public static final String INTENT_CONNECT_VPN = "com.tailscale.ipn.CONNECT_VPN";
    public static final String INTENT_DISCONNECT_VPN = "com.tailscale.ipn.DISCONNECT_VPN";

    @Override
    public void onReceive(Context context, Intent intent) {
        WorkManager workManager = WorkManager.getInstance(context);

        // On the relevant action, start the relevant worker, which can stay active for longer than this receiver can.
        if (intent.getAction() == INTENT_CONNECT_VPN) {
            workManager.enqueue(new OneTimeWorkRequest.Builder(StartVPNWorker.class).build());
        } else if (intent.getAction() == INTENT_DISCONNECT_VPN) {
            workManager.enqueue(new OneTimeWorkRequest.Builder(StopVPNWorker.class).build());
        }
    }
}
