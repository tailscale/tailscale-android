// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.tailscale.ipn.UninitializedApp.Companion.STATUS_CHANNEL_ID
import com.tailscale.ipn.UninitializedApp.Companion.STATUS_NOTIFICATION_ID
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.resume

class UseExitNodeWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val app = UninitializedApp.get()

        val exitNodeName = inputData.getString(EXIT_NODE_NAME)

        val exitNodeId =
            if (exitNodeName.isNullOrEmpty()) {
                null
            } else {
                if (!app.isAbleToStartVPN()) {
                    return Result.failure(
                        Data.Builder().putString(
                            ERROR_KEY, app.getString(R.string.vpn_is_not_ready_to_start)
                        ).build()
                    )
                }

                val netmap = Notifier.netmap.value ?: return Result.failure(
                    Data.Builder().putString(
                        ERROR_KEY, app.getString(R.string.tailscale_is_not_setup)
                    ).build()
                )

                val peers = netmap.Peers ?: return Result.failure(
                    Data.Builder().putString(
                        ERROR_KEY, app.getString(R.string.no_peers_found)
                    ).build()
                )


                val filteredPeers = peers.filter { it.displayName == exitNodeName }.toList()

                when {
                    filteredPeers.isEmpty() -> {
                        return Result.failure(
                            Data.Builder().putString(ERROR_KEY, app.getString(R.string.no_peers_with_name_found, exitNodeName)).build()
                        )
                    }

                    filteredPeers.size > 1 -> {
                        return Result.failure(
                            Data.Builder().putString(ERROR_KEY, app.getString(R.string.multiple_peers_with_name_found, exitNodeName)).build()
                        )
                    }

                    !filteredPeers[0].isExitNode -> {
                        return Result.failure(
                            Data.Builder().putString(ERROR_KEY, app.getString(R.string.peer_with_name_is_not_an_exit_node, exitNodeName)).build()
                        )
                    }
                }
                filteredPeers[0].StableID
            }

        val allowLanAccess = inputData.getBoolean(ALLOW_LAN_ACCESS, false)
        val prefsOut = Ipn.MaskedPrefs()
        prefsOut.ExitNodeID = exitNodeId
        prefsOut.ExitNodeAllowLANAccess = allowLanAccess

        val scope = CoroutineScope(kotlinx.coroutines.currentCoroutineContext())

        val result: String? =
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                Client(scope).editPrefs(prefsOut) { editResult ->
                    val err =
                        if (editResult.isFailure) {
                            editResult.exceptionOrNull()?.message
                        } else {
                            null
                        }
                    if (cont.isActive) {
                        cont.resume(err)
                    }
                }
            }

    return if (result != null) {
      val intent =
          Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
          }
      val pendingIntent: PendingIntent =
          PendingIntent.getActivity(
              app, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

      val notification =
          NotificationCompat.Builder(app, STATUS_CHANNEL_ID)
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

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // notification just so that there is no exception on android 11 and older (api 30 and older)
        // it will be only briefly visible in the real world because the intent finishes almost instantly
        // https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#backwards-compat
        val app = UninitializedApp.get()
        val notification =
            NotificationCompat.Builder(app, STATUS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(app.getString(R.string.changing_exit_node_notification))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        return ForegroundInfo(STATUS_NOTIFICATION_ID, notification)
    }
  companion object {
    const val EXIT_NODE_NAME = "EXIT_NODE_NAME"
    const val ALLOW_LAN_ACCESS = "ALLOW_LAN_ACCESS"
    const val ERROR_KEY = "error"
  }
}
