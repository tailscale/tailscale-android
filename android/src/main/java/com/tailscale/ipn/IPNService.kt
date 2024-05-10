// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.system.OsConstants
import libtailscale.Libtailscale
import java.util.UUID

open class IPNService : VpnService(), libtailscale.IPNService {
  private val randomID: String = UUID.randomUUID().toString()

  override fun id(): String {
    return randomID
  }

  override fun onCreate() {
    super.onCreate()
    // grab app to make sure it initializes
    App.get()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
      when (intent?.action) {
        ACTION_STOP_VPN -> {
          App.get().setWantRunning(false)
          close()
          START_NOT_STICKY
        }
        ACTION_START_VPN -> {
          App.get().setWantRunning(true)
          Libtailscale.requestVPN(this)
          START_STICKY
        }
        "android.net.VpnService" -> {
          // This means we were started by Android due to Always On VPN.
          // Get the application to make sure it's been initialized, then
          // request the VPN.
          App.get()
          Libtailscale.requestVPN(this)
          START_STICKY
        }
        else -> {
          // This means that we were restarted after the service was killed
          // (potentially due to OOM).
          if (UninitializedApp.get().isAbleToStartVPN()) {
            App.get()
            Libtailscale.requestVPN(this)
            START_STICKY
          } else {
            START_NOT_STICKY
          }
        }
      }

  override fun close() {
    stopForeground(true)
    Libtailscale.serviceDisconnect(this)
  }

  override fun onDestroy() {
    close()
    super.onDestroy()
  }

  override fun onRevoke() {
    close()
    super.onRevoke()
  }

  private fun configIntent(): PendingIntent {
    return PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  private fun disallowApp(b: Builder, name: String) {
    try {
      b.addDisallowedApplication(name)
    } catch (e: PackageManager.NameNotFoundException) {}
  }

  override fun newBuilder(): VPNServiceBuilder {
    val b: Builder =
        Builder()
            .setConfigureIntent(configIntent())
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      b.setMetered(false) // Inherit the metered status from the underlying networks.
    }
    b.setUnderlyingNetworks(null) // Use all available networks.

    // RCS/Jibe https://github.com/tailscale/tailscale/issues/2322
    disallowApp(b, "com.google.android.apps.messaging")

    // Stadia https://github.com/tailscale/tailscale/issues/3460
    disallowApp(b, "com.google.stadia.android")

    // Android Auto https://github.com/tailscale/tailscale/issues/3828
    disallowApp(b, "com.google.android.projection.gearhead")

    // GoPro https://github.com/tailscale/tailscale/issues/2554
    disallowApp(b, "com.gopro.smarty")

    // Sonos https://github.com/tailscale/tailscale/issues/2548
    disallowApp(b, "com.sonos.acr")
    disallowApp(b, "com.sonos.acr2")

    // Google Chromecast https://github.com/tailscale/tailscale/issues/3636
    disallowApp(b, "com.google.android.apps.chromecast.app")
    return VPNServiceBuilder(b)
  }

  companion object {
    const val ACTION_START_VPN = "com.tailscale.ipn.START_VPN"
    const val ACTION_STOP_VPN = "com.tailscale.ipn.STOP_VPN"
  }
}
