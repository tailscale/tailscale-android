// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import android.app.Application;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.provider.MediaStore;
import android.provider.Settings;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.VpnService;
import android.view.View;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import android.webkit.MimeTypeMap;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;

import java.security.GeneralSecurityException;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import androidx.browser.customtabs.CustomTabsIntent;

import org.gioui.Gio;

public class App extends Application {
	private final static String PEER_TAG = "peer";

	static final String STATUS_CHANNEL_ID = "tailscale-status";
	static final int STATUS_NOTIFICATION_ID = 1;

	static final String NOTIFY_CHANNEL_ID = "tailscale-notify";
	static final int NOTIFY_NOTIFICATION_ID = 2;

	private static final String FILE_CHANNEL_ID = "tailscale-files";
	private static final int FILE_NOTIFICATION_ID = 3;

	private final static Handler mainHandler = new Handler(Looper.getMainLooper());

	@Override public void onCreate() {
		super.onCreate();
		// Load and initialize the Go library.
		Gio.init(this);
		registerNetworkCallback();

		createNotificationChannel(NOTIFY_CHANNEL_ID, "Notifications", NotificationManagerCompat.IMPORTANCE_DEFAULT);
		createNotificationChannel(STATUS_CHANNEL_ID, "VPN Status", NotificationManagerCompat.IMPORTANCE_LOW);
		createNotificationChannel(FILE_CHANNEL_ID, "File transfers", NotificationManagerCompat.IMPORTANCE_DEFAULT);

	}

	private void registerNetworkCallback() {
		BroadcastReceiver connectivityChanged = new BroadcastReceiver() {
			@Override public void onReceive(Context ctx, Intent intent) {
				boolean noconn = intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
				onConnectivityChanged(!noconn);
			}
		};
		registerReceiver(connectivityChanged, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
	}

	public void startVPN() {
		Intent intent = new Intent(this, IPNService.class);
		intent.setAction(IPNService.ACTION_CONNECT);
		startService(intent);
	}

	public void stopVPN() {
		Intent intent = new Intent(this, IPNService.class);
		intent.setAction(IPNService.ACTION_DISCONNECT);
		startService(intent);
	}

	// encryptToPref a byte array of data using the Jetpack Security
	// library and writes it to a global encrypted preference store.
	public void encryptToPref(String prefKey, String plaintext) throws IOException, GeneralSecurityException {
		getEncryptedPrefs().edit().putString(prefKey, plaintext).commit();
	}

	// decryptFromPref decrypts a encrypted preference using the Jetpack Security
	// library and returns the plaintext.
	public String decryptFromPref(String prefKey) throws IOException, GeneralSecurityException {
		return getEncryptedPrefs().getString(prefKey, null);
	}

	private SharedPreferences getEncryptedPrefs() throws IOException, GeneralSecurityException {
		MasterKey key = new MasterKey.Builder(this)
			.setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
			.build();

		return EncryptedSharedPreferences.create(
			this,
			"secret_shared_prefs",
			key,
			EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
			EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
		);
	}

	void setTileReady(boolean ready) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
			return;
		}
		QuickToggleService.setReady(this, ready);
	}

	void setTileStatus(boolean status) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
			return;
		}
		QuickToggleService.setStatus(this, status);
	}

	String getHostname() {
		String userConfiguredDeviceName = getUserConfiguredDeviceName();
		if (!isEmpty(userConfiguredDeviceName)) return userConfiguredDeviceName;

		return getModelName();
	}

	String getModelName() {
		String manu = Build.MANUFACTURER;
		String model = Build.MODEL;
		// Strip manufacturer from model.
		int idx = model.toLowerCase().indexOf(manu.toLowerCase());
		if (idx != -1) {
			model = model.substring(idx + manu.length());
			model = model.trim();
		}
		return manu + " " + model;
	}

	String getOSVersion() {
		return Build.VERSION.RELEASE;
	}

	// get user defined nickname from Settings
	// returns null if not available
	private String getUserConfiguredDeviceName() {
		String nameFromSystemBluetooth = Settings.System.getString(getContentResolver(), "bluetooth_name");
		String nameFromSecureBluetooth = Settings.Secure.getString(getContentResolver(), "bluetooth_name");
		String nameFromSystemDevice = Settings.Secure.getString(getContentResolver(), "device_name");

		if (!isEmpty(nameFromSystemBluetooth)) return nameFromSystemBluetooth;
		if (!isEmpty(nameFromSecureBluetooth)) return nameFromSecureBluetooth;
		if (!isEmpty(nameFromSystemDevice)) return nameFromSystemDevice;
		return null;
	}

	private static boolean isEmpty(String str) {
		return str == null || str.length() == 0;
	}

	// attachPeer adds a Peer fragment for tracking the Activity
	// lifecycle.
	void attachPeer(Activity act) {
		act.runOnUiThread(new Runnable() {
			@Override public void run() {
				FragmentTransaction ft = act.getFragmentManager().beginTransaction();
				ft.add(new Peer(), PEER_TAG);
				ft.commit();
				act.getFragmentManager().executePendingTransactions();
			}
		});
	}

	boolean isChromeOS() {
		return getPackageManager().hasSystemFeature("android.hardware.type.pc");
	}

	void prepareVPN(Activity act, int reqCode) {
		act.runOnUiThread(new Runnable() {
			@Override public void run() {
				Intent intent = VpnService.prepare(act);
				if (intent == null) {
					onVPNPrepared();
				} else {
					startActivityForResult(act, intent, reqCode);
				}
			}
		});
	}

	static void startActivityForResult(Activity act, Intent intent, int request) {
		Fragment f = act.getFragmentManager().findFragmentByTag(PEER_TAG);
		f.startActivityForResult(intent, request);
	}

	void showURL(Activity act, String url) {
		act.runOnUiThread(new Runnable() {
			@Override public void run() {
				CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();
				int headerColor = 0xff496495;
				builder.setToolbarColor(headerColor);
				CustomTabsIntent intent = builder.build();
				intent.launchUrl(act, Uri.parse(url));
			}
		});
	}

	// getPackageSignatureFingerprint returns the first package signing certificate, if any.
	byte[] getPackageCertificate() throws Exception {
		PackageInfo info;
		info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
		for (Signature signature : info.signatures) {
			return signature.toByteArray();
		}
		return null;
	}

	String insertMedia(String name, String mimeType) throws IOException {
		ContentResolver resolver = getContentResolver();
		ContentValues contentValues = new ContentValues();
		contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
		if (!"".equals(mimeType)) {
			contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
		}
		Uri root = MediaStore.Files.getContentUri("external");
		return resolver.insert(root, contentValues).toString();
	}

	int openUri(String uri, String mode) throws IOException {
		ContentResolver resolver = getContentResolver();
		return resolver.openFileDescriptor(Uri.parse(uri), mode).detachFd();
	}

	void deleteUri(String uri) {
		ContentResolver resolver = getContentResolver();
		resolver.delete(Uri.parse(uri), null, null);
	}

	public void notifyFile(String uri, String msg) {
		Intent fileIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
		PendingIntent pending = PendingIntent.getActivity(this, 0, fileIntent, PendingIntent.FLAG_UPDATE_CURRENT);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, FILE_CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_notification)
			.setContentTitle("File received")
			.setContentText(msg)
			.setContentIntent(pending)
			.setAutoCancel(true)
			.setOnlyAlertOnce(true)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT);

		NotificationManagerCompat nm = NotificationManagerCompat.from(this);
		nm.notify(FILE_NOTIFICATION_ID, builder.build());
	}

	private void createNotificationChannel(String id, String name, int importance) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return;
		}
		NotificationChannel channel = new NotificationChannel(id, name, importance);
		NotificationManagerCompat nm = NotificationManagerCompat.from(this);
		nm.createNotificationChannel(channel);
	}

	static native void onVPNPrepared();
	private static native void onConnectivityChanged(boolean connected);
	static native void onShareIntent(int nfiles, int[] types, String[] mimes, String[] items, String[] names, long[] sizes);
}
