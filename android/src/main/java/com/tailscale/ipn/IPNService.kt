// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import libtailscale.Libtailscale
import java.util.UUID

open class IPNService : VpnService(), libtailscale.IPNService {
  private val randomID: String = UUID.randomUUID().toString()

  override fun id(): String {
    return randomID
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent != null && ACTION_STOP_VPN == intent.action) {
      (applicationContext as App).autoConnect = false
      close()
      return START_NOT_STICKY
    }
    val app = applicationContext as App
    if (intent != null && "android.net.VpnService" == intent.action) {
      // Start VPN and connect to it due to Always-on VPN
      val i = Intent(IPNReceiver.INTENT_CONNECT_VPN)
      i.setPackage(packageName)
      i.setClass(applicationContext, IPNReceiver::class.java)
      sendBroadcast(i)
      Libtailscale.requestVPN(this)
      app.setWantRunning(true)
      return START_STICKY
    }
    Libtailscale.requestVPN(this)
    if (app.vpnReady && app.autoConnect) {
      app.setWantRunning(true)
    }
    return START_STICKY
  }

  override fun close() {
    stopForeground(true)
    Libtailscale.serviceDisconnect(this)
    val app = applicationContext as App
    app.setWantRunning(false)
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
    } catch (_: PackageManager.NameNotFoundException) {}
  }

  override fun newBuilder(): VPNServiceBuilder {
    val b: Builder =
        Builder()
            .setConfigureIntent(configIntent())
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        b.setMetered(false) // Inherit the metered status from the underlying networks.
    b.setUnderlyingNetworks(null) // Use all available networks.

    DisallowedApps.apps.forEach { disallowApp(b, it) }

    return VPNServiceBuilder(b)
  }

  fun notify(title: String?, message: String?) {
    val builder: NotificationCompat.Builder =
        NotificationCompat.Builder(this, App.NOTIFY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(configIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    val nm: NotificationManagerCompat = NotificationManagerCompat.from(this)
    nm.notify(App.NOTIFY_NOTIFICATION_ID, builder.build())
  }

  fun updateStatusNotification(title: String?, message: String?) {
    val builder: NotificationCompat.Builder =
        NotificationCompat.Builder(this, App.STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(configIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
    startForeground(App.STATUS_NOTIFICATION_ID, builder.build())
  }

  companion object {
    const val ACTION_REQUEST_VPN = "com.tailscale.ipn.REQUEST_VPN"
    const val ACTION_STOP_VPN = "com.tailscale.ipn.STOP_VPN"
  }
}
