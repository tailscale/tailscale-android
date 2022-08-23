// Copyright (c) 2021 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import android.content.Context;
import android.Manifest;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;
import android.net.VpnService;
import java.lang.reflect.Method;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Drawable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.lang.StringBuilder;

public class AppsConfig {
	private class AppConfig {
		boolean allowed;
		String icon;
		String packageName;
		String label;
	}

	private static final String PREFS_NAME = "disallowedApps";

	private Context ctx;
	private List<AppConfig> config;
	private SharedPreferences sp;

	public AppsConfig(Context ctx, SharedPreferences sp) {
		this.ctx = ctx;
		this.sp = sp;
		PackageManager pm = this.ctx.getPackageManager();

		config = new ArrayList<AppConfig>();
		List<ApplicationInfo> instApps = getInstalledApps(pm);

		for (int i = 0;i < instApps.size(); i++) {
			ApplicationInfo appinfo = instApps.get(i);
			AppConfig ac = new AppConfig();
			ac.packageName = appinfo.packageName;
			ac.allowed = true;
			ac.icon = retrieveAppIcon(pm,appinfo);
			CharSequence label = pm.getApplicationLabel(appinfo);
			if(label != null){
				ac.label = label.toString();
			} else {
				ac.label = ac.packageName;
			}
			config.add(ac);
		}
		readAppsConfig();
	}

	public VpnService.Builder build(VpnService.Builder b){
		if(b == null)
			return b;

		for (int i = 0;i < config.size(); i++) {
			AppConfig ac = config.get(i);
			if(ac.allowed == false){
				try {
                        		b.addDisallowedApplication(ac.packageName);
                		} catch (PackageManager.NameNotFoundException e) {
                		
				}
			}
		}

		return b;
	}
	
	public int getTotalApps(){
		return config.size();
	}

	public boolean appIsAllowed(int i){
		AppConfig ac = config.get(i);
		if(ac != null){
			return ac.allowed;
		}

		return false;
	}

	public String getPackageLabel(int i){
		AppConfig ac = config.get(i);
		if(ac != null){
			return ac.label;
		}

		return null;
	}

	public String getPackageName(int i){
		AppConfig ac = config.get(i);
		if(ac != null){
			return ac.packageName;
		}

		return null;
	}

	// Get the icon as a png encoded as a base64 string
	public String getAppIcon(String packageName) {
		if(config == null)
			return null;

		for(int i = 0;i < config.size(); i++) {
			AppConfig ac = config.get(i);
			if(ac.packageName.equals(packageName)){
				return ac.icon;
			}
		}

		return null;
	}

	// Allow,Disallow application to connect to the VPN
	public void setApp(String packageName, boolean allowed) {
		if(config == null || packageName == null || packageName.length() == 0)
			return;
		
		Log.d("com.tailscale.ipn", "Disabling " + packageName);
		AppConfig ac = null;

		Log.d("com.tailscale.ipn", "size: " + config.size());
		for (int i = 0;i < config.size(); i++) {
			ac = config.get(i);
			if(ac.packageName.equals(packageName) && 
				ac.allowed != allowed){
				//Log.d("com.tailscale.ipn", packageName);
				ac.allowed = allowed;
				config.set(i, ac);
				writeAppsConfig();
				return;
			}
		}
	}

	//returns a String like packageName;packageName;... or ""
	private String getDisallowedApps() {
		StringBuilder sb = new StringBuilder();
		
		for (int i = 0;i < config.size(); i++) {
			AppConfig ac = config.get(i);
			if(ac.allowed == false){
				sb.append(ac.packageName);
				sb.append(";");
			}
		}
		
		//Log.d("com.tailscale.ipn", sb.toString());
		return sb.toString();
	}

	// persists blacklisted apps in the settings
	public void writeAppsConfig() {
		//Log.d("com.tailscale.ipn", "writeAppsConfig");
		if(sp == null)
			return;
		//Log.d("com.tailscale.ipn", getDisallowedApps());
		sp.edit().putString(PREFS_NAME, getDisallowedApps()).apply();
	}

	// read apps blacklisted in the settings
	private void readAppsConfig() {
		if(sp == null)
			return;
		
		String disallowedApps = sp.getString(PREFS_NAME, "");
		
		if(disallowedApps.length() != 0){
			List<String> strapps = 
				Arrays.asList(disallowedApps.split(";"));
			
			if(strapps.size() > 0){
				for(int i = 0; i < strapps.size(); i++){
					for (int j = 0;j < config.size(); j++) {
						AppConfig ac = config.get(j);
						if(ac.packageName.equals(strapps.get(i))){
							ac.allowed = false;
							config.set(j, ac);
						}
					}
				}
			}
		}
	}

	// Just for debug purposes prints all the apps and state
	public void printConfig() {
		if (config != null) {
			for (int i = 0;i < config.size(); i++) {
				AppConfig ac = config.get(i);
				Log.v("com.tailscale.ipn", 
					ac.packageName + " " + ac.allowed);
				Log.v("com.tailscale.ipn", ac.icon);
			}
		}
	}

	// Retrieves the icon in png encoded in base64 from android using a PackageManager
	private String retrieveAppIcon(PackageManager pm, ApplicationInfo info) {
    		Drawable d = pm.getApplicationIcon(info);
    		Bitmap mutableBitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
    		Canvas canvas = new Canvas(mutableBitmap);
    		d.setBounds(0, 0, 48, 48);
    		d.draw(canvas);
    		//Bitmap to png to ByteArrayOutputStream to byte[] to base64 String
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		//100 is the quality, but it is ignored for PNG
		mutableBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
		//Return the png encoded as a base64 string
		return Base64.encodeToString(baos.toByteArray(),Base64.DEFAULT);
	}

	// Returns a sorted list of installed apps with access to The Internet
	private List<ApplicationInfo> getInstalledApps(PackageManager pm) {
		if(this.ctx == null)
			return null;
		
		if(pm == null)
			pm = this.ctx.getPackageManager();

		// Initialize variables
		List<ApplicationInfo> ret = null;
		List<ApplicationInfo> insApps = 
			pm.getInstalledApplications(PackageManager.GET_META_DATA);

		// This is done in the OpenVPN code
		int androidSystemUid = 0;
		ret = new ArrayList<ApplicationInfo>();

		try {
			ApplicationInfo system = 
				pm.getApplicationInfo("android", PackageManager.GET_META_DATA);
			if(system != null){
		  		ret.add(system);
				androidSystemUid = system.uid;
			}
		} catch (PackageManager.NameNotFoundException e) {
			// it's ok if the "android" app doesn't exists
		}

		for (int i = 0;i < insApps.size(); i++){
			ApplicationInfo app = insApps.get(i);
			if (pm.checkPermission(
					Manifest.permission.INTERNET, 
					app.packageName) 
				== PackageManager.PERMISSION_GRANTED 
					&& app.uid != androidSystemUid){
				if(app.packageName != "android")
					ret.add(app);
			}
		}
		
		Collections.sort(ret, new ApplicationInfo.DisplayNameComparator(pm));
		return ret;
	}
}
