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
        val app = UninitializedApp.get()
        suspend fun runAndGetResult(): String? {
            val exitNodeName = inputData.getString(EXIT_NODE_NAME)

            val exitNodeId = if (exitNodeName.isNullOrEmpty()) {
                null
            } else {
                if (!app.isAbleToStartVPN()) {
                    return app.getString(R.string.vpn_is_not_ready_to_start)
                }

                val peers =
                    (Notifier.netmap.value
                        ?: run { return@runAndGetResult app.getString(R.string.tailscale_is_not_setup) })
                        .Peers ?: run { return@runAndGetResult app.getString(R.string.no_peers_found) }

                val filteredPeers = peers.filter {
                    it.displayName == exitNodeName
                }.toList()

                if (filteredPeers.isEmpty()) {
                    return app.getString(R.string.no_peers_with_name_found, exitNodeName)
                } else if (filteredPeers.size > 1) {
                    return app.getString(R.string.multiple_peers_with_name_found, exitNodeName)
                } else if (!filteredPeers[0].isExitNode) {
                    return app.getString(
                        R.string.peer_with_name_is_not_an_exit_node,
                        exitNodeName
                    )
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
            val intent =
                Intent(app, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            val pendingIntent: PendingIntent =
                PendingIntent.getActivity(
                    app, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val notification = NotificationCompat.Builder(app, STATUS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(app.getString(R.string.use_exit_node_intent_failed))
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
