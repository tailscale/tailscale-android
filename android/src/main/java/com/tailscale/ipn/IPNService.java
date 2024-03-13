// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.util.Log;
import android.os.Build;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.system.OsConstants;
import androidx.work.WorkManager;
import androidx.work.OneTimeWorkRequest;

import org.gioui.GioActivity;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class IPNService extends VpnService {
	public static final String ACTION_REQUEST_VPN = "com.tailscale.ipn.REQUEST_VPN";
	public static final String ACTION_STOP_VPN = "com.tailscale.ipn.STOP_VPN";

	@Override public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null && ACTION_STOP_VPN.equals(intent.getAction())) {
			((App)getApplicationContext()).autoConnect = false;
			close();
			return START_NOT_STICKY;
		} 
		if (intent != null && "android.net.VpnService".equals(intent.getAction())) {
			// Start VPN and connect to it due to Always-on VPN
			Intent i = new Intent(IPNReceiver.INTENT_CONNECT_VPN);
			i.setPackage(getPackageName());
			i.setClass(getApplicationContext(), com.tailscale.ipn.IPNReceiver.class);
			sendBroadcast(i);
			requestVPN();
			connect();
			return START_STICKY;
		}
		requestVPN();
		App app = ((App)getApplicationContext());
		if (app.vpnReady && app.autoConnect) {
			connect();
		}
		return START_STICKY;
	}

	private void close() {
		stopForeground(true);
		disconnect();
	}

	@Override public void onDestroy() {
		close();
		super.onDestroy();
	}

	@Override public void onRevoke() {
		close();
		super.onRevoke();
	}

	private PendingIntent configIntent() {
		return PendingIntent.getActivity(this, 0, new Intent(this, IPNActivity.class),
			PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
	}

	private void disallowApp(VpnService.Builder b, String name) {
		try {
			b.addDisallowedApplication(name);
		} catch (PackageManager.NameNotFoundException e) {
			return;
		}
	}

	protected VpnService.Builder newBuilder() {
		VpnService.Builder b = new VpnService.Builder()
			.setConfigureIntent(configIntent())
			.allowFamily(OsConstants.AF_INET)
			.allowFamily(OsConstants.AF_INET6);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
			b.setMetered(false); // Inherit the metered status from the underlying networks.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
			b.setUnderlyingNetworks(null); // Use all available networks.

		// RCS/Jibe https://github.com/tailscale/tailscale/issues/2322
		this.disallowApp(b, "com.google.android.apps.messaging");

		// Stadia https://github.com/tailscale/tailscale/issues/3460
		this.disallowApp(b, "com.google.stadia.android");

		// Android Auto https://github.com/tailscale/tailscale/issues/3828
		this.disallowApp(b, "com.google.android.projection.gearhead");

		// GoPro https://github.com/tailscale/tailscale/issues/2554
		this.disallowApp(b, "com.gopro.smarty");

		// Sonos https://github.com/tailscale/tailscale/issues/2548
		this.disallowApp(b, "com.sonos.acr");
		this.disallowApp(b, "com.sonos.acr2");

		// Google Chromecast https://github.com/tailscale/tailscale/issues/3636
		this.disallowApp(b, "com.google.android.apps.chromecast.app");

		return b;
	}

	public void notify(String title, String message) {
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, App.NOTIFY_CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_notification)
			.setContentTitle(title)
			.setContentText(message)
			.setContentIntent(configIntent())
			.setAutoCancel(true)
			.setOnlyAlertOnce(true)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT);

		NotificationManagerCompat nm = NotificationManagerCompat.from(this);
		nm.notify(App.NOTIFY_NOTIFICATION_ID, builder.build());
	}

	public void updateStatusNotification(String title, String message) {
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, App.STATUS_CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_notification)
			.setContentTitle(title)
			.setContentText(message)
			.setContentIntent(configIntent())
			.setPriority(NotificationCompat.PRIORITY_LOW);

		startForeground(App.STATUS_NOTIFICATION_ID, builder.build());
	}

	private native void requestVPN();

	private native void disconnect();
	private native void connect();
}
