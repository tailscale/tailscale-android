// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class QuickToggleService extends TileService {
    // lock protects the static fields below it.
    private static final Object lock = new Object();

    // isRunning tracks whether the VPN is running.
    private static boolean isRunning;

    // Key for shared preference that tracks whether or not we're able to start
    // the VPN (i.e. we're logged in and machine is authorized).
    public static final String ABLE_TO_START_VPN_KEY = "ableToStartVPN";

    // File for shared preference that tracks whether or not we're able to start
    // the VPN (i.e. we're logged in and machine is authorized).
    public static final String QUICK_TOGGLE = "quicktoggle";

    // currentTile tracks getQsTile while service is listening.
    private static Tile currentTile;

    // Request code for opening activity.
    private static int reqCode = 0;

    private static void updateTile(Context ctx) {
        Tile t;
        boolean act;
        synchronized (lock) {
            t = currentTile;
            act = isRunning && getAbleToStartVPN(ctx);
        }
        if (t == null) {
            return;
        }
        t.setLabel("Tailscale");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            t.setSubtitle(act ? ctx.getString(R.string.connected) : ctx.getString(R.string.not_connected));
        }
        t.setState(act ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }

    /*
     * setAbleToStartVPN remembers whether or not we're able to start the VPN
     * by storing this in a shared preference. This allows us to check this
     * value without needing a fully initialized instance of the application.
     */
    static void setAbleToStartVPN(Context ctx, boolean rdy) {
        SharedPreferences prefs = getSharedPreferences(ctx);
        prefs.edit().putBoolean(ABLE_TO_START_VPN_KEY, rdy).apply();
        updateTile(ctx);
    }

    static boolean getAbleToStartVPN(Context ctx) {
        SharedPreferences prefs = getSharedPreferences(ctx);
        return prefs.getBoolean(ABLE_TO_START_VPN_KEY, false);
    }

    static SharedPreferences getSharedPreferences(Context ctx) {
        return ctx.getSharedPreferences(QUICK_TOGGLE, Context.MODE_PRIVATE);
    }

    static void setVPNRunning(Context ctx, boolean running) {
        synchronized (lock) {
            isRunning = running;
        }
        updateTile(ctx);
    }

    @Override
    public void onStartListening() {
        synchronized (lock) {
            currentTile = getQsTile();
        }
        updateTile(this.getApplicationContext());
    }

    @Override
    public void onStopListening() {
        synchronized (lock) {
            currentTile = null;
        }
    }

    @Override
    public void onClick() {
        boolean r;
        synchronized (lock) {
            r = getAbleToStartVPN(this);
        }
        if (r) {
            // Get the application to make sure it initializes
            App.getApplication();
            onTileClick();
        } else {
            // Start main activity.
            Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(PendingIntent.getActivity(this, reqCode, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            } else {
                startActivityAndCollapse(i);
            }
        }
    }

    private void onTileClick() {
        boolean act;
        synchronized (lock) {
            act = getAbleToStartVPN(this) && isRunning;
        }
        Intent i = new Intent(act ? IPNReceiver.INTENT_DISCONNECT_VPN : IPNReceiver.INTENT_CONNECT_VPN);
        i.setPackage(getPackageName());
        i.setClass(getApplicationContext(), com.tailscale.ipn.IPNReceiver.class);
        sendBroadcast(i);
    }
}
