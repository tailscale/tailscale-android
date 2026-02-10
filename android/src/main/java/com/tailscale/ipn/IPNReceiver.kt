// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * IPNReceiver allows external applications to start the VPN.
 */
class IPNReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent?) {
    if (intent == null) return

    val workManager = WorkManager.getInstance(context)
    when (intent.action) {
      INTENT_CONNECT_VPN -> {
        val req =
          OneTimeWorkRequest.Builder(StartVPNWorker::class.java)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(WORK_CONNECT)
            .build()

        workManager.enqueueUniqueWork(WORK_CONNECT, ExistingWorkPolicy.REPLACE, req)
      }

      INTENT_DISCONNECT_VPN -> {
        val req =
          OneTimeWorkRequest.Builder(StopVPNWorker::class.java)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(WORK_DISCONNECT)
            .build()

        workManager.enqueueUniqueWork(WORK_DISCONNECT, ExistingWorkPolicy.REPLACE, req)
      }

      INTENT_USE_EXIT_NODE -> {
        val exitNode = intent.getStringExtra("exitNode")
        val allowLanAccess = intent.getBooleanExtra("allowLanAccess", false)

        val input =
          Data.Builder()
            .putString(UseExitNodeWorker.EXIT_NODE_NAME, exitNode)
            .putBoolean(UseExitNodeWorker.ALLOW_LAN_ACCESS, allowLanAccess)
            .build()

        val req =
          OneTimeWorkRequest.Builder(UseExitNodeWorker::class.java)
            .setInputData(input)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(WORK_USE_EXIT_NODE)
            .build()

        workManager.enqueueUniqueWork(WORK_USE_EXIT_NODE, ExistingWorkPolicy.REPLACE, req)
      }
    }
  }

  companion object {
    const val INTENT_CONNECT_VPN = "com.tailscale.ipn.CONNECT_VPN"
    const val INTENT_DISCONNECT_VPN = "com.tailscale.ipn.DISCONNECT_VPN"
    private const val INTENT_USE_EXIT_NODE = "com.tailscale.ipn.USE_EXIT_NODE"

    // Unique work names prevent connect/disconnect flapping from enqueuing a long backlog.
    private const val WORK_CONNECT = "ipn-connect-vpn"
    private const val WORK_DISCONNECT = "ipn-disconnect-vpn"
    private const val WORK_USE_EXIT_NODE = "ipn-use-exit-node"
  }
}
