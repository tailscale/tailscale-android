// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier

// Taps follow the same rules as QuickToggleService so the widget and tile behave alike.
class VPNToggleWidgetProvider : AppWidgetProvider() {
  companion object {
    private const val ACTION_TOGGLE = "com.tailscale.ipn.WIDGET_VPN_TOGGLE"

    @JvmStatic
    fun updateAll(context: Context) {
      val manager = AppWidgetManager.getInstance(context)
      val ids = manager.getAppWidgetIds(ComponentName(context, VPNToggleWidgetProvider::class.java))
      if (ids.isEmpty()) return
      manager.updateAppWidget(ids, buildViews(context))
    }

    private fun buildViews(context: Context): RemoteViews {
      val connected = UninitializedApp.get().isAbleToStartVPN() && isVPNRunning()
      val views = RemoteViews(context.packageName, R.layout.widget_vpn_toggle)
      views.setTextViewText(
          R.id.widget_status,
          context.getString(if (connected) R.string.connected else R.string.not_connected),
      )
      views.setImageViewResource(
          R.id.widget_icon,
          if (connected) R.drawable.ic_notification else R.drawable.ic_notification_disabled,
      )
      views.setOnClickPendingIntent(R.id.widget_root, clickIntent(context))
      return views
    }

    // Decided at draw time because a broadcast receiver can't reliably start an activity, so
    // cases that need the app (logged out, VPN permission missing) must launch it directly.
    private fun clickIntent(context: Context): PendingIntent {
      if (!canToggle(context)) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        return PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      }
      val toggle = Intent(context, VPNToggleWidgetProvider::class.java).setAction(ACTION_TOGGLE)
      return PendingIntent.getBroadcast(
          context,
          0,
          toggle,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }

    private fun isVPNRunning(): Boolean =
        Notifier.state.value.let { it == Ipn.State.Starting || it == Ipn.State.Running }

    private fun canToggle(context: Context): Boolean =
        UninitializedApp.get().isAbleToStartVPN() && VpnService.prepare(context) == null
  }

  override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
    manager.updateAppWidget(appWidgetIds, buildViews(context))
  }

  override fun onReceive(context: Context, intent: Intent) {
    super.onReceive(context, intent)
    if (intent.action != ACTION_TOGGLE) return
    // State may have changed since the widget was drawn; redraw so the next tap opens the app.
    if (!canToggle(context)) {
      updateAll(context)
      return
    }
    val app = App.get()
    if (isVPNRunning()) app.stopVPN() else app.startVPN()
  }
}
