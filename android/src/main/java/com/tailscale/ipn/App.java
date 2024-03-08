// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.app.Application;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.RestrictionsManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.res.Configuration;
import android.provider.MediaStore;
import android.provider.Settings;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.VpnService;
import android.view.View;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import android.Manifest;
import android.webkit.MimeTypeMap;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;

import java.lang.StringBuilder;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;

import java.security.GeneralSecurityException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import androidx.core.content.ContextCompat;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import androidx.browser.customtabs.CustomTabsIntent;

import com.tailscale.ipn.mdm.AlwaysNeverUserDecidesSetting;
import com.tailscale.ipn.mdm.BooleanSetting;
import com.tailscale.ipn.mdm.MDMSettings;
import com.tailscale.ipn.mdm.ShowHideSetting;
import com.tailscale.ipn.mdm.StringSetting;

import com.tailscale.ipn.ui.localapi.LocalApiClient;

public class App extends Application {
	private static final String PEER_TAG = "peer";

	static final String STATUS_CHANNEL_ID = "tailscale-status";
	static final int STATUS_NOTIFICATION_ID = 1;

	static final String NOTIFY_CHANNEL_ID = "tailscale-notify";
	static final int NOTIFY_NOTIFICATION_ID = 2;

	private static final String FILE_CHANNEL_ID = "tailscale-files";
	private static final int FILE_NOTIFICATION_ID = 3;

	private static final Handler mainHandler = new Handler(Looper.getMainLooper());

	private ConnectivityManager connectivityManager;
	public DnsConfig dns = new DnsConfig();
	public DnsConfig getDnsConfigObj() { return this.dns; }


	static App _application;
	public static App getApplication() {
		return _application;
	}

	@Override public void onCreate()  {
		super.onCreate();

		System.loadLibrary("tailscale");

		String dataDir = this.getFilesDir().getAbsolutePath();
		byte[] dataDirUTF8;
		try {
			dataDirUTF8 = dataDir.getBytes("UTF-8");
			initBackend(dataDirUTF8, this);
		} catch (Exception e) {
			android.util.Log.d("tailscale","Error getting directory");
		}

		this.connectivityManager = (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);
		setAndRegisterNetworkCallbacks();

		createNotificationChannel(NOTIFY_CHANNEL_ID, "Notifications", NotificationManagerCompat.IMPORTANCE_DEFAULT);
		createNotificationChannel(STATUS_CHANNEL_ID, "VPN Status", NotificationManagerCompat.IMPORTANCE_LOW);
		createNotificationChannel(FILE_CHANNEL_ID, "File transfers", NotificationManagerCompat.IMPORTANCE_DEFAULT);

		_application = this;
	}

	// requestNetwork attempts to find the best network that matches the passed NetworkRequest. It is possible that
	// this might return an unusuable network, eg a captive portal.
	private void setAndRegisterNetworkCallbacks() {
		connectivityManager.requestNetwork(dns.getDNSConfigNetworkRequest(), new ConnectivityManager.NetworkCallback(){
			@Override
			public void onAvailable(Network network){
				super.onAvailable(network);
				StringBuilder sb = new StringBuilder("");
				LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
				List<InetAddress> dnsList = linkProperties.getDnsServers();
				for (InetAddress ip : dnsList) {
					sb.append(ip.getHostAddress()).append(" ");
				}
				String searchDomains = linkProperties.getDomains();
				if (searchDomains != null) {
					sb.append("\n");
					sb.append(searchDomains);
				}

				dns.updateDNSFromNetwork(sb.toString());
				onDnsConfigChanged();
			}

			@Override
			public void onLost(Network network) {
				super.onLost(network);
				onDnsConfigChanged();
			}
		});
	}

	public void startVPN() {
		Intent intent = new Intent(this, IPNService.class);
		intent.setAction(IPNService.ACTION_REQUEST_VPN);
		startService(intent);
	}

	public void stopVPN() {
		Intent intent = new Intent(this, IPNService.class);
		intent.setAction(IPNService.ACTION_STOP_VPN);
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

	public SharedPreferences getEncryptedPrefs() throws IOException, GeneralSecurityException {
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

	public boolean autoConnect = false;
	public boolean vpnReady = false;

	void setTileReady(boolean ready) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
			return;
		}
		QuickToggleService.setReady(this, ready);
		android.util.Log.d("App", "Set Tile Ready: " + ready + " " + autoConnect);

		vpnReady = ready;
		if (ready && autoConnect) {
			startVPN();
		}
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
		String nameFromSystemDevice = Settings.Secure.getString(getContentResolver(), "device_name");
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

	void requestWriteStoragePermission(Activity act) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
			// We can write files without permission.
			return;
		}
		if (ContextCompat.checkSelfPermission(act, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
			return;
		}
		act.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, IPNActivity.WRITE_STORAGE_RESULT);
	}

	String insertMedia(String name, String mimeType) throws IOException {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ContentResolver resolver = getContentResolver();
			ContentValues contentValues = new ContentValues();
			contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
			if (!"".equals(mimeType)) {
				contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
			}
			Uri root = MediaStore.Files.getContentUri("external");
			return resolver.insert(root, contentValues).toString();
		} else {
			File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
			dir.mkdirs();
			File f = new File(dir, name);
			return Uri.fromFile(f).toString();
		}
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
		Intent viewIntent;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
		} else {
			// uri is a file:// which is not allowed to be shared outside the app.
			viewIntent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
		}
		PendingIntent pending = PendingIntent.getActivity(this, 0, viewIntent, PendingIntent.FLAG_UPDATE_CURRENT);
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

	public void createNotificationChannel(String id, String name, int importance) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return;
		}
		NotificationChannel channel = new NotificationChannel(id, name, importance);
		NotificationManagerCompat nm = NotificationManagerCompat.from(this);
		nm.createNotificationChannel(channel);
	}

	static native void initBackend(byte[] dataDir, Context context);
	static native void onVPNPrepared();
	private static native void onDnsConfigChanged();
	static native void onShareIntent(int nfiles, int[] types, String[] mimes, String[] items, String[] names, long[] sizes);
	static native void onWriteStorageGranted();

        // Returns details of the interfaces in the system, encoded as a single string for ease
        // of JNI transfer over to the Go environment.
        //
        // Example:
        // rmnet_data0 10 2000 true false false false false | fe80::4059:dc16:7ed3:9c6e%rmnet_data0/64
        // dummy0 3 1500 true false false false false | fe80::1450:5cff:fe13:f891%dummy0/64
        // wlan0 30 1500 true true false false true | fe80::2f60:2c82:4163:8389%wlan0/64 10.1.10.131/24
        // r_rmnet_data0 21 1500 true false false false false | fe80::9318:6093:d1ad:ba7f%r_rmnet_data0/64
        // rmnet_data2 12 1500 true false false false false | fe80::3c8c:44dc:46a9:9907%rmnet_data2/64
        // r_rmnet_data1 22 1500 true false false false false | fe80::b6cd:5cb0:8ae6:fe92%r_rmnet_data1/64
        // rmnet_data1 11 1500 true false false false false | fe80::51f2:ee00:edce:d68b%rmnet_data1/64
        // lo 1 65536 true false true false false | ::1/128 127.0.0.1/8
        // v4-rmnet_data2 68 1472 true true false true true | 192.0.0.4/32
        //
        // Where the fields are:
        // name ifindex mtu isUp hasBroadcast isLoopback isPointToPoint hasMulticast | ip1/N ip2/N ip3/N;
	String getInterfacesAsString() {
            List<NetworkInterface> interfaces;
            try {
                interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            } catch (Exception e) {
                return "";
            }

            StringBuilder sb = new StringBuilder("");
            for (NetworkInterface nif : interfaces) {
                try {
                    // Android doesn't have a supportsBroadcast() but the Go net.Interface wants
                    // one, so we say the interface has broadcast if it has multicast.
                    sb.append(String.format(java.util.Locale.ROOT, "%s %d %d %b %b %b %b %b |", nif.getName(),
                                   nif.getIndex(), nif.getMTU(), nif.isUp(), nif.supportsMulticast(),
                                   nif.isLoopback(), nif.isPointToPoint(), nif.supportsMulticast()));

                    for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                        // InterfaceAddress == hostname + "/" + IP
                        String[] parts = ia.toString().split("/", 0);
                        if (parts.length > 1) {
                            sb.append(String.format(java.util.Locale.ROOT, "%s/%d ", parts[1], ia.getNetworkPrefixLength()));
                        }
                    }
                } catch (Exception e) {
                    // TODO(dgentry) should log the exception not silently suppress it.
                    continue;
                }
                sb.append("\n");
            }

            return sb.toString();
        }

	boolean isTV() {
		UiModeManager mm = (UiModeManager)getSystemService(UI_MODE_SERVICE);
		return mm.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
	}

	/*
	The following methods are called by the syspolicy handler from Go via JNI.
	 */

	boolean getSyspolicyBooleanValue(String key) {
		RestrictionsManager manager = (RestrictionsManager) this.getSystemService(Context.RESTRICTIONS_SERVICE);
		MDMSettings mdmSettings = new MDMSettings(manager);
		BooleanSetting setting = BooleanSetting.valueOf(key);
		return mdmSettings.get(setting);
	}

	String getSyspolicyStringValue(String key) {
		RestrictionsManager manager = (RestrictionsManager) this.getSystemService(Context.RESTRICTIONS_SERVICE);
		MDMSettings mdmSettings = new MDMSettings(manager);

        // Before looking for a StringSetting matching the given key, Go could also be
        // asking us for either a AlwaysNeverUserDecidesSetting or a ShowHideSetting.
        // Check the enum cases for these two before looking for a StringSetting.
        try {
            AlwaysNeverUserDecidesSetting anuSetting = AlwaysNeverUserDecidesSetting.valueOf(key);
            return mdmSettings.get(anuSetting).getValue();
        } catch (IllegalArgumentException eanu) { // AlwaysNeverUserDecidesSetting does not exist
            try {
                ShowHideSetting showHideSetting = ShowHideSetting.valueOf(key);
                return mdmSettings.get(showHideSetting).getValue();
            } catch (IllegalArgumentException esh) {
                try {
                    StringSetting stringSetting = StringSetting.valueOf(key);
                    String value = mdmSettings.get(stringSetting);
                    return Objects.requireNonNullElse(value, "");
                } catch (IllegalArgumentException estr) {
                    android.util.Log.d("MDM", key+" is not defined on Android. Returning empty.");
                    return "";
                }
            }
        }
	}
}
