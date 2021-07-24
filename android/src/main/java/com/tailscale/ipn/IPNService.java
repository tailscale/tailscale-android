// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import android.os.Build;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.system.OsConstants;

import org.gioui.GioActivity;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class IPNService extends VpnService {
	public static final String ACTION_CONNECT = "com.tailscale.ipn.CONNECT";
	public static final String ACTION_DISCONNECT = "com.tailscale.ipn.DISCONNECT";

	@Override public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
			close();
			return START_NOT_STICKY;
		} else if (intent != null && ACTION_CONNECT.equals(intent.getAction())) {
			connect();
			return START_STICKY;
		} else if (intent != null) {
			// we're being started due to Always-on VPN
			connectAtBoot();
			return START_STICKY;
		}
		// dunno what this is.
		connect();
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
		return PendingIntent.getActivity(this, 0, new Intent(this, GioActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
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

	private native void connect();
	private native void disconnect();
	private native void connectAtBoot();
}
