// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.tailscale.ipn.UninitializedApp.Companion.STATUS_CHANNEL_ID
import com.tailscale.ipn.UninitializedApp.Companion.STATUS_EXIT_NODE_FAILURE_NOTIFICATION_ID
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

class UseExitNodeWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        suspend fun runAndGetResult(): String? {
            val exitNodeName = inputData.getString(EXIT_NODE_NAME)

            val exitNodeId = if (exitNodeName.isNullOrEmpty()) {
                null
            } else {
                if (!UninitializedApp.get().isAbleToStartVPN()) {
                    return "VPN is not ready to start"
                }

                val peers =
                    (Notifier.netmap.value
                        ?: run { return@runAndGetResult "Tailscale is not setup" })
                        .Peers ?: run { return@runAndGetResult "No peers found" }

                val filteredPeers = peers.filter {
                    it.displayName == exitNodeName
                }.toList()

                if (filteredPeers.isEmpty()) {
                    return "No peers with name $exitNodeName found"
                } else if (filteredPeers.size > 1) {
                    return "Multiple peers with name $exitNodeName found"
                } else if (!filteredPeers[0].isExitNode) {
                    return "Peer with name $exitNodeName is not an exit node"
                }

                filteredPeers[0].StableID
            }

            val allowLanAccess = inputData.getBoolean(ALLOW_LAN_ACCESS, false)
            val prefsOut = Ipn.MaskedPrefs()
            prefsOut.ExitNodeID = exitNodeId
            prefsOut.ExitNodeAllowLANAccess = allowLanAccess

            val scope = CoroutineScope(Dispatchers.Default + Job())
            var result: String? = null
            Client(scope).editPrefs(prefsOut) {
                result = if (it.isFailure) {
                    it.exceptionOrNull()?.message
                } else {
                    null
                }
            }

            scope.coroutineContext[Job]?.join()

            return result
        }

        val result = runAndGetResult()

        return if (result != null) {
            val app = UninitializedApp.get()
            val intent =
                Intent(app, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            val pendingIntent: PendingIntent =
                PendingIntent.getActivity(
                    app, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val notification = NotificationCompat.Builder(app, STATUS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Use Exit Node Intent Failed")
                .setContentText(result)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .build()

            app.notifyStatus(notification)

            Result.failure(Data.Builder().putString(ERROR_KEY, result).build())
        } else {
            Result.success()
        }
    }

    companion object {
        const val EXIT_NODE_NAME = "EXIT_NODE_NAME"
        const val ALLOW_LAN_ACCESS = "ALLOW_LAN_ACCESS"
        const val ERROR_KEY = "error"
    }
}
