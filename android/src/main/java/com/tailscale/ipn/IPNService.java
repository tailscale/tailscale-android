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
		return PendingIntent.getActivity(this, 0, new Intent(this, IPNActivity.class),
			PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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

		App app = (App) this.getApplication();

		// RCS/Jibe https://github.com/tailscale/tailscale/issues/2322
		app.setupApp("com.google.android.apps.messaging", false);

		// Stadia https://github.com/tailscale/tailscale/issues/3460
		app.setupApp("com.google.stadia.android", false);

		// Android Auto https://github.com/tailscale/tailscale/issues/3828
		app.setupApp("com.google.android.projection.gearhead", false);

		// GoPro https://github.com/tailscale/tailscale/issues/2554
		app.setupApp("com.gopro.smarty", false);

		// Sonos https://github.com/tailscale/tailscale/issues/2548
		app.setupApp("com.sonos.acr", false);
		app.setupApp("com.sonos.acr2", false);

		// Google Chromecast https://github.com/tailscale/tailscale/issues/3636
		app.setupApp("com.google.android.apps.chromecast.app", false);

		// Apply changes
		b = app.acfg.build(b);

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
}
