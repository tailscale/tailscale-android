// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class QuickToggleService extends TileService {
    // lock protects the static fields below it.
    private static final Object lock = new Object();

    // isRunning tracks whether the VPN is running.
    private static boolean isRunning;

    // currentTile tracks getQsTile while service is listening.
    private static Tile currentTile;

    public static void updateTile() {
        var app = UninitializedApp.get();
        Tile t;
        boolean act;
        synchronized (lock) {
            t = currentTile;
            act = isRunning && app.isAbleToStartVPN();
        }
        if (t == null) {
            return;
        }
        t.setLabel("Tailscale");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            t.setSubtitle(act ? app.getString(R.string.connected) : app.getString(R.string.not_connected));
        }
        t.setState(act ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }

    static void setVPNRunning(boolean running) {
        synchronized (lock) {
            isRunning = running;
        }
        updateTile();
    }

    @Override
    public void onStartListening() {
        synchronized (lock) {
            currentTile = getQsTile();
        }
        updateTile();
    }

    @Override
    public void onStopListening() {
        synchronized (lock) {
            currentTile = null;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onClick() {
        boolean r;
        synchronized (lock) {
            r = UninitializedApp.get().isAbleToStartVPN();
        }
        if (r) {
            // Get the application to make sure it initializes
            App.get();
            onTileClick();
        } else {
            // Start main activity.
            Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Request code for opening activity.
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            } else {
                // Deprecated, but still required for older versions.
                startActivityAndCollapse(i);
            }
        }
    }

    private void onTileClick() {
        UninitializedApp app = UninitializedApp.get();
        boolean needsToStop;
        synchronized (lock) {
            needsToStop = app.isAbleToStartVPN() && isRunning;
        }
        if (needsToStop) {
            app.stopVPN();
        } else {
            app.startVPN();
        }
    }
}
