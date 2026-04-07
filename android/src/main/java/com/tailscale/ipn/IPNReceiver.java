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

        if (Objects.equals(intent.getAction(), AdbTcpHttpTestContract.ACTION_RUN_TEST) && !BuildConfig.DEBUG) {
            return;
        }

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
            OneTimeWorkRequest req =
                    new OneTimeWorkRequest.Builder(StopVPNWorker.class)
                            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                            .addTag(WORK_DISCONNECT)
                            .build();

            workManager.enqueueUniqueWork(WORK_DISCONNECT, ExistingWorkPolicy.REPLACE, req);

        } else if (Objects.equals(action, INTENT_USE_EXIT_NODE)) {
            String exitNode = intent.getStringExtra("exitNode");
            boolean allowLanAccess = intent.getBooleanExtra("allowLanAccess", false);

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
        } else if (Objects.equals(action, AdbTcpHttpTestContract.ACTION_RUN_TEST)) {
            String requestId = intent.getStringExtra(AdbTcpHttpTestContract.EXTRA_REQUEST_ID);
            if (requestId == null || requestId.trim().isEmpty()) {
                requestId = String.valueOf(System.currentTimeMillis());
            }
            Data input =
                    new Data.Builder()
                            .putString(AdbTcpHttpTestContract.EXTRA_SCENARIO, intent.getStringExtra(AdbTcpHttpTestContract.EXTRA_SCENARIO))
                            .putString(AdbTcpHttpTestContract.EXTRA_REQUEST_ID, requestId)
                            .putString(AdbTcpHttpTestContract.EXTRA_HOST, intent.getStringExtra(AdbTcpHttpTestContract.EXTRA_HOST))
                            .putInt(AdbTcpHttpTestContract.EXTRA_PORT, intent.getIntExtra(AdbTcpHttpTestContract.EXTRA_PORT, -1))
                            .putString(AdbTcpHttpTestContract.EXTRA_PROTOCOL, intent.getStringExtra(AdbTcpHttpTestContract.EXTRA_PROTOCOL))
                            .putString(AdbTcpHttpTestContract.EXTRA_PATH, intent.getStringExtra(AdbTcpHttpTestContract.EXTRA_PATH))
                            .putString(AdbTcpHttpTestContract.EXTRA_PAYLOAD, intent.getStringExtra(AdbTcpHttpTestContract.EXTRA_PAYLOAD))
                            .putString(AdbTcpHttpTestContract.EXTRA_HOST_HEADER, intent.getStringExtra(AdbTcpHttpTestContract.EXTRA_HOST_HEADER))
                            .putLong(AdbTcpHttpTestContract.EXTRA_TIMEOUT_MS, intent.getLongExtra(AdbTcpHttpTestContract.EXTRA_TIMEOUT_MS, AdbTcpHttpTestContract.DEFAULT_TIMEOUT_MS))
                            .putBoolean(AdbTcpHttpTestContract.EXTRA_SOCKS_ENABLED, intent.getBooleanExtra(AdbTcpHttpTestContract.EXTRA_SOCKS_ENABLED, AdbTcpHttpTestContract.DEFAULT_SOCKS_ENABLED))
                            .putBoolean(AdbTcpHttpTestContract.EXTRA_PREVIEW_ONLY, intent.getBooleanExtra(AdbTcpHttpTestContract.EXTRA_PREVIEW_ONLY, false))
                            .build();

            OneTimeWorkRequest req =
                    new OneTimeWorkRequest.Builder(AdbTcpHttpTestWorker.class)
                            .setInputData(input)
                            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                            .addTag(AdbTcpHttpTestContract.WORK_RUN_TEST)
                            .addTag(requestId)
                            .build();

            workManager.enqueueUniqueWork(
                    AdbTcpHttpTestContract.WORK_RUN_TEST + "-" + requestId,
                    ExistingWorkPolicy.REPLACE,
                    req);
        }
    }
}
