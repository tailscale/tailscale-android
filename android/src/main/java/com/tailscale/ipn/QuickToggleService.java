// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class QuickToggleService extends TileService {
    // lock protects the static fields below it.
    private static final Object lock = new Object();
    // Active tracks whether the VPN is active.
    private static boolean active;
    // Ready tracks whether the tailscale backend is
    // ready to switch on/off.
    private static boolean ready;
    // currentTile tracks getQsTile while service is listening.
    private static Tile currentTile;
    // Request code for opening activity.
    private static int reqCode = 0;

    private static void updateTile(Context ctx) {
        Tile t;
        boolean act;
        synchronized (lock) {
            t = currentTile;
            act = active && ready;
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

    static void setReady(Context ctx, boolean rdy) {
        synchronized (lock) {
            ready = rdy;
        }
        updateTile(ctx);
    }

    static void setStatus(Context ctx, boolean act) {
        synchronized (lock) {
            active = act;
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
            r = ready;
        }
        if (r) {
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
            act = active && ready;
        }
        Intent i = new Intent(act ? IPNReceiver.INTENT_DISCONNECT_VPN : IPNReceiver.INTENT_CONNECT_VPN);
        i.setPackage(getPackageName());
        i.setClass(getApplicationContext(), com.tailscale.ipn.IPNReceiver.class);
        sendBroadcast(i);
    }
}
