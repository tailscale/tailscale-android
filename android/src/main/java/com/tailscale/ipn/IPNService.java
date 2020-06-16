// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import android.os.Build;
import android.app.PendingIntent;
import android.app.NotificationChannel;
import android.content.Intent;
import android.net.VpnService;
import android.system.OsConstants;

import org.gioui.GioActivity;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class IPNService extends VpnService {
	public static final String ACTION_CONNECT = "com.tailscale.ipn.CONNECT";
	public static final String ACTION_DISCONNECT = "com.tailscale.ipn.DISCONNECT";

	private static final String STATUS_CHANNEL_ID = "tailscale-status";
	private static final String STATUS_CHANNEL_NAME = "VPN Status";
	private static final int STATUS_NOTIFICATION_ID = 1;

	private static final String NOTIFY_CHANNEL_ID = "tailscale-notify";
	private static final String NOTIFY_CHANNEL_NAME = "Notifications";
	private static final int NOTIFY_NOTIFICATION_ID = 2;

	@Override public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
			close();
			return START_NOT_STICKY;
		}
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
		return new VpnService.Builder()
			.setConfigureIntent(configIntent())
			.allowFamily(OsConstants.AF_INET)
			.allowFamily(OsConstants.AF_INET6);
	}

	public void notify(String title, String message) {
		createNotificationChannel(NOTIFY_CHANNEL_ID, NOTIFY_CHANNEL_NAME, NotificationManagerCompat.IMPORTANCE_DEFAULT);

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFY_CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_notification)
			.setContentTitle(title)
			.setContentText(message)
			.setContentIntent(configIntent())
			.setAutoCancel(true)
			.setOnlyAlertOnce(true)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT);

		NotificationManagerCompat nm = NotificationManagerCompat.from(this);
		nm.notify(NOTIFY_NOTIFICATION_ID, builder.build());
	}

	public void updateStatusNotification(String title, String message) {
		createNotificationChannel(STATUS_CHANNEL_ID, STATUS_CHANNEL_NAME, NotificationManagerCompat.IMPORTANCE_LOW);

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_notification)
			.setContentTitle(title)
			.setContentText(message)
			.setContentIntent(configIntent())
			.setPriority(NotificationCompat.PRIORITY_LOW);

		startForeground(STATUS_NOTIFICATION_ID, builder.build());
	}

	private void createNotificationChannel(String id, String name, int importance) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return;
		}
		NotificationChannel channel = new NotificationChannel(id, name, importance);
		NotificationManagerCompat nm = NotificationManagerCompat.from(this);
		nm.createNotificationChannel(channel);
	}

	private native void connect();
	private native void disconnect();
}
