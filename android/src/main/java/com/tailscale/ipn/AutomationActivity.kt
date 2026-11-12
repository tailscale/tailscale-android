// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import com.tailscale.ipn.util.TSLog

/**
 * AutomationActivity is a transparent, no-UI trampoline that connects or disconnects the VPN and
 * finishes immediately. It backs the launcher long-press shortcuts and can also be invoked from
 * Samsung Modes and Routines or Tasker.
 *
 * It is an activity rather than an extension of [IPNReceiver]'s broadcasts because starting an
 * activity reliably wakes an app that the OS has force-stopped or put into deep sleep, e.g. under
 * Samsung battery management. The brief foreground also lets connect start the VPN service without
 * hitting the Android 12+ restriction on starting a foreground service from the background, which
 * the broadcast path can trip when the app has been idle.
 */
class AutomationActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    when (intent?.action) {
      IPNReceiver.INTENT_CONNECT_VPN -> connect()
      IPNReceiver.INTENT_DISCONNECT_VPN -> UninitializedApp.get().stopVPN()
      else -> TSLog.w(TAG, "unknown action: ${intent?.action}")
    }

    // Never show any UI: finish before this activity becomes visible.
    finish()
  }

  /**
   * Starts the VPN directly when it is ready and consent is already granted. Otherwise (not set up,
   * or consent not yet granted) opens the app, which handles login and the consent prompt.
   */
  private fun connect() {
    val app = UninitializedApp.get()
    if (app.isAbleToStartVPN() && VpnService.prepare(this) == null) {
      app.startVPN()
    } else {
      launchMainActivity()
    }
  }

  private fun launchMainActivity() {
    val intent =
        Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    try {
      startActivity(intent)
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to launch MainActivity: $e")
    }
  }

  companion object {
    private const val TAG = "AutomationActivity"
  }
}
