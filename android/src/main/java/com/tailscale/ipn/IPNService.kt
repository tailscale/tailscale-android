// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.system.OsConstants
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import libtailscale.Libtailscale

open class IPNService : VpnService(), libtailscale.IPNService {
  private val TAG = "IPNService"
  private val randomID: String = UUID.randomUUID().toString()
  private lateinit var app: App
  val scope = CoroutineScope(Dispatchers.IO)

  override fun id(): String {
    return randomID
  }

  override fun updateVpnStatus(status: Boolean) {
    app.getAppScopedViewModel().setVpnActive(status)
  }

  override fun onCreate() {
    super.onCreate()
    // grab app to make sure it initializes
    app = App.get()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
      when (intent?.action) {
        ACTION_STOP_VPN -> {
          app.setWantRunning(false)
          close()
          START_NOT_STICKY
        }
        ACTION_RESTART_VPN -> {
          app.setWantRunning(false) {
            close()
            app.startVPN()
          }
          START_NOT_STICKY
        }
        ACTION_START_VPN -> {
          scope.launch { showForegroundNotification() }
          app.setWantRunning(true)
          Libtailscale.requestVPN(this)
          START_STICKY
        }
        "android.net.VpnService" -> {
          // This means we were started by Android due to Always On VPN.
          // We show a non-foreground notification because we weren't
          // started as a foreground service.
          scope.launch {
            // Collect the first value of hideDisconnectAction asynchronously.
            val hideDisconnectAction = MDMSettings.forceEnabled.flow.first()
            val exitNodeName =
                UninitializedApp.getExitNodeName(Notifier.prefs.value, Notifier.netmap.value)
            app.notifyStatus(true, hideDisconnectAction.value, exitNodeName)
          }
          app.setWantRunning(true)
          Libtailscale.requestVPN(this)
          START_STICKY
        }
        else -> {
          // This means that we were restarted after the service was killed
          // (potentially due to OOM).
          if (UninitializedApp.get().isAbleToStartVPN()) {
            scope.launch { showForegroundNotification() }
            App.get()
            Libtailscale.requestVPN(this)
            START_STICKY
          } else {
            START_NOT_STICKY
          }
        }
      }

  override fun close() {
    Notifier.setState(Ipn.State.Stopping)
    disconnectVPN()
    Libtailscale.serviceDisconnect(this)
  }

  override fun disconnectVPN() {
    stopSelf()
  }

  override fun onDestroy() {
    close()
    updateVpnStatus(false)
    super.onDestroy()
  }

  override fun onRevoke() {
    close()
    updateVpnStatus(false)
    super.onRevoke()
  }

  private fun setVpnPrepared(isPrepared: Boolean) {
    app.getAppScopedViewModel().setVpnPrepared(isPrepared)
  }

  private fun showForegroundNotification(
      hideDisconnectAction: Boolean,
      exitNodeName: String? = null
  ) {
    try {
      startForeground(
          UninitializedApp.STATUS_NOTIFICATION_ID,
          UninitializedApp.get().buildStatusNotification(true, hideDisconnectAction, exitNodeName))
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to start foreground service: $e")
    }
  }

  private fun showForegroundNotification() {
    val hideDisconnectAction = MDMSettings.forceEnabled.flow.value.value
    val exitNodeName = UninitializedApp.getExitNodeName(Notifier.prefs.value, Notifier.netmap.value)
    showForegroundNotification(hideDisconnectAction, exitNodeName)
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
    } catch (e: PackageManager.NameNotFoundException) {
      TSLog.d(TAG, "Failed to add disallowed application: $e")
    }
  }

    private fun allowApp(b: Builder, name: String) {
        try {
            b.addAllowedApplication(name)
        } catch (e: PackageManager.NameNotFoundException) {
            TSLog.d(TAG, "Failed to add allowed application: $e")
        }
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

        val app = UninitializedApp.get()

        val mdmAllowed = MDMSettings.includedPackages.flow.value.value?.
        split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val mdmDisallowed = MDMSettings.excludedPackages.flow.value.value?.
        split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        val splitEnabled = app.isSplitTunnelEnabled()
        val mode = app.getSplitTunnelMode()

        when {
            mdmAllowed.isNotEmpty() -> {
                TSLog.d(TAG, "MDM include mode, allowed = $mdmAllowed")
                mdmAllowed.forEach { pkg ->
                    TSLog.d(TAG, "Including app via MDM: $pkg")
                    allowApp(b, pkg)
                }
            }

            mdmDisallowed.isNotEmpty() -> {
                val effectiveDisallowed = app.builtInDisallowedPackageNames + mdmDisallowed
                TSLog.d(TAG, "MDM exclude mode, disallowed = $effectiveDisallowed")
                effectiveDisallowed.forEach { pkg ->
                    TSLog.d(TAG, "Disallowing app via MDM: $pkg")
                    disallowApp(b, pkg)
                }
            }

            !splitEnabled -> {
                TSLog.d(TAG, "Split tunneling disabled; using built-in disallowed only")
                app.builtInDisallowedPackageNames.forEach { pkg ->
                    TSLog.d(TAG, "Disallowing built-in app: $pkg")
                    disallowApp(b, pkg)
                }
            }

            mode == UninitializedApp.SplitTunnelMode.INCLUDE -> {
                val userAllowed = app.allowedPackageNames()
                TSLog.d(TAG, "User INCLUDE mode; allowed = $userAllowed")
                userAllowed.forEach { pkg ->
                    TSLog.d(TAG, "Including app via user INCLUDE: $pkg")
                    allowApp(b, pkg)
                }
            }

            else -> {
                val effectiveDisallowed = app.disallowedPackageNames()
                TSLog.d(TAG, "User EXCLUDE mode; disallowed = $effectiveDisallowed")
                effectiveDisallowed.forEach { pkg ->
                    TSLog.d(TAG, "Disallowing app via user EXCLUDE/built-in: $pkg")
                    disallowApp(b, pkg)
                }
            }
        }

        return VPNServiceBuilder(b)
    }

  companion object {
    const val ACTION_START_VPN = "com.tailscale.ipn.START_VPN"
    const val ACTION_STOP_VPN = "com.tailscale.ipn.STOP_VPN"
    const val ACTION_RESTART_VPN = "com.tailscale.ipn.RESTART_VPN"
  }
}
