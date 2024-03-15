// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.net.VpnService
import androidx.lifecycle.lifecycleScope
import android.util.Log
import android.os.Build
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.system.OsConstants
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequest
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.tailscale.ipn.ui.service.IpnServiceManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class IPNService : VpnService() {
    private val vpnServiceJob = Job()
    private val vpnServiceScope = CoroutineScope(Dispatchers.Default + vpnServiceJob)

    private var ipnServiceManager: IpnServiceManager? = null
    override fun onCreate() {
        super.onCreate()
        ipnServiceManager = IpnServiceManager(vpnServiceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && ACTION_STOP_VPN == intent.getAction()) {
            (getApplicationContext() as App).autoConnect = false
            close()
            return START_NOT_STICKY
        }
        if (intent != null && "android.net.VpnService" == intent.getAction()) {
            // Start VPN and connect to it due to Always-on VPN
            val i = Intent(IPNReceiver.INTENT_CONNECT_VPN)
            i.setPackage(getPackageName())
            i.setClass(getApplicationContext(), IPNReceiver::class.java)
            sendBroadcast(i)
            requestVPN()
            ipnServiceManager?.connect()
            return START_STICKY
        }
        requestVPN()
        val app = applicationContext as App
        if (app.vpnReady && app.autoConnect) {
            ipnServiceManager?.connect()
        }
        return START_STICKY
    }

    private fun close() {
        stopForeground(true)
        disconnect()
    }

    override fun onDestroy() {
        close()
        vpnServiceJob.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        close()
        super.onRevoke()
    }

    private fun configIntent(): PendingIntent {
        return PendingIntent.getActivity(this, 0, Intent(this, IPNActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun disallowApp(b: Builder, name: String) {
        try {
            b.addDisallowedApplication(name)
        } catch (e: Exception) {
            return
        }
    }

    protected fun newBuilder(): Builder {
        val b: Builder = Builder()
                .setConfigureIntent(configIntent())
                .allowFamily(OsConstants.AF_INET)
                .allowFamily(OsConstants.AF_INET6)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false) // Inherit the metered status from the underlying networks.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) b.setUnderlyingNetworks(null) // Use all available networks.

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
        return b
    }

    fun notify(title: String?, message: String?) {
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, App.NOTIFY_CHANNEL_ID)
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
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, App.STATUS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(configIntent())
                .setPriority(NotificationCompat.PRIORITY_LOW)
        startForeground(App.STATUS_NOTIFICATION_ID, builder.build())
    }

    private external fun requestVPN()
    private external fun disconnect()

    companion object {
        const val ACTION_REQUEST_VPN = "com.tailscale.ipn.REQUEST_VPN"
        const val ACTION_STOP_VPN = "com.tailscale.ipn.STOP_VPN"
    }
}
