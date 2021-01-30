// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import android.content.Context;
import android.content.ComponentName;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

public class QuickToggleService extends TileService {
	private static AtomicBoolean active = new AtomicBoolean();
	private static AtomicReference<Tile> currentTile = new AtomicReference<Tile>();

	@Override public void onStartListening() {
		currentTile.set(getQsTile());
		updateTile();
	}

	@Override public void onStopListening() {
		currentTile.set(null);
	}

	@Override public void onClick() {
		onTileClick();
	}

	private static void updateTile() {
		Tile t = currentTile.get();
		if (t == null) {
			return;
		}
		t.setState(active.get() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
		t.updateTile();
	}

	static void setStatus(Context ctx, boolean wantRunning) {
		active.set(wantRunning);
		updateTile();
	}

	private static native void onTileClick();
}
