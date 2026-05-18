// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;

import com.tailscale.ipn.ui.model.Ipn;
import com.tailscale.ipn.ui.model.Netmap;
import com.tailscale.ipn.ui.notifier.Notifier;

import java.util.Objects;

/**
 * IPNReceiver allows external applications to start the VPN.
 */
public class IPNReceiver extends BroadcastReceiver {

    public static final String INTENT_CONNECT_VPN = "com.tailscale.ipn.CONNECT_VPN";
    public static final String INTENT_DISCONNECT_VPN = "com.tailscale.ipn.DISCONNECT_VPN";
    private static final String INTENT_USE_EXIT_NODE = "com.tailscale.ipn.USE_EXIT_NODE";

    // Unique work names prevent connect/disconnect flapping from enqueuing a long backlog.
    private static final String WORK_CONNECT = "ipn-connect-vpn";
    private static final String WORK_DISCONNECT = "ipn-disconnect-vpn";
    private static final String WORK_USE_EXIT_NODE = "ipn-use-exit-node";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        final WorkManager workManager = WorkManager.getInstance(context);
        final String action = intent.getAction();

        if (Objects.equals(action, INTENT_CONNECT_VPN)) {
            OneTimeWorkRequest req =
                    new OneTimeWorkRequest.Builder(StartVPNWorker.class)
                            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                            .addTag(WORK_CONNECT)
                            .build();

            workManager.enqueueUniqueWork(WORK_CONNECT, ExistingWorkPolicy.REPLACE, req);

        } else if (Objects.equals(action, INTENT_DISCONNECT_VPN)) {
            // If we're already disconnected, skip triggering the worker to avoid overwriting the status notification
            // with the "Stopping Tailscale VPN…" one.
            boolean running = UninitializedApp.get().getAppScopedViewModel().getVpnActive().getValue();
            if (!running) {
                return;
            }

            OneTimeWorkRequest req =
                    new OneTimeWorkRequest.Builder(StopVPNWorker.class)
                            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                            .addTag(WORK_DISCONNECT)
                            .build();

            workManager.enqueueUniqueWork(WORK_DISCONNECT, ExistingWorkPolicy.REPLACE, req);

        } else if (Objects.equals(action, INTENT_USE_EXIT_NODE)) {
            String exitNode = intent.getStringExtra("exitNode");
            if (exitNode != null && exitNode.isEmpty()) exitNode = null;
            boolean allowLanAccess = intent.getBooleanExtra("allowLanAccess", false);


            Ipn.Prefs currentPrefs = Notifier.INSTANCE.getPrefs().getValue();
            Netmap.NetworkMap currentNetmap = Notifier.INSTANCE.getNetmap().getValue();
            String currentExitNodeName = UninitializedApp.Companion.getExitNodeName(currentPrefs, currentNetmap);
            boolean currentAllowLan = false;
            if (currentPrefs != null) {
                currentAllowLan = currentPrefs.getExitNodeAllowLANAccess();
            }
            // If the exit node configuration is the same as requested, skip triggering the worker
            // to avoid overwriting the status notification with the "Changing exit node…" one.
            if (Objects.equals(exitNode, currentExitNodeName) && allowLanAccess == currentAllowLan) {
                return;
            }

            Data input =
                    new Data.Builder()
                            .putString(UseExitNodeWorker.EXIT_NODE_NAME, exitNode)
                            .putBoolean(UseExitNodeWorker.ALLOW_LAN_ACCESS, allowLanAccess)
                            .build();

            OneTimeWorkRequest req =
                    new OneTimeWorkRequest.Builder(UseExitNodeWorker.class)
                            .setInputData(input)
                            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                            .addTag(WORK_USE_EXIT_NODE)
                            .build();

            workManager.enqueueUniqueWork(WORK_USE_EXIT_NODE, ExistingWorkPolicy.REPLACE, req);
        }
    }
}
