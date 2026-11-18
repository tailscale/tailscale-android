// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tailscale.ipn.util.TSLog

/**
 * A backstop for the case where the app dies and nothing brings it back.
 *
 * The in-process watchdog lives in [IPNService], so it dies with the app. In
 * practice Android's always-on VPN restarts the app after an OOM kill — the
 * release build survived 15 `LOW_MEMORY` exits that way — so this worker is not
 * the primary mechanism. It covers the narrower case where always-on VPN is off
 * or its restart fails.
 *
 * It never reconnects a tunnel the user turned off: it acts only when
 * [UninitializedApp.wantsRunning] is true, which mirrors the user's last
 * explicit intent.
 */
class WatchdogRecoveryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val app = UninitializedApp.get()
    if (!app.wantsRunning()) {
      TSLog.d(TAG, "user does not want the tunnel up; nothing to do")
      return Result.success()
    }
    if (isVpnUp()) {
      TSLog.d(TAG, "a VPN is already up; nothing to do")
      return Result.success()
    }
    TSLog.w(TAG, "tunnel wanted but no VPN is up; restarting")
    app.startVPN()
    return Result.success()
  }

  private fun isVpnUp(): Boolean {
    val cm = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps: NetworkCapabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
  }

  companion object {
    const val TAG = "TSWatchdog"
    const val WORK_NAME = "watchdog-recovery"
  }
}
