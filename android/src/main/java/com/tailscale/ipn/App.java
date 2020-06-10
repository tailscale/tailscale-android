// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.net.ConnectivityManager;
import android.os.Build;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;

import java.security.GeneralSecurityException;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.gioui.Gio;

public class App extends Application {
	@Override public void onCreate() {
		super.onCreate();
		// Load and initialize the Go library.
		Gio.init(this);
		registerNetworkCallback();
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
		String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);

		return EncryptedSharedPreferences.create(
				"secret_shared_prefs",
				masterKeyAlias,
				this,
				EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
				EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
		);
	}

	String getHostname() {
		return Build.MANUFACTURER + " " + Build.MODEL;
	}

	private static native void onConnectivityChanged(boolean connected);
}
