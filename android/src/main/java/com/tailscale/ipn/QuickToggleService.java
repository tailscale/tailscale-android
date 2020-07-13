// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import android.content.Context;
import android.content.ComponentName;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class QuickToggleService extends TileService {
	private static boolean active;

	@Override public void onStartListening() {
		Tile t = getQsTile();
		t.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
		t.updateTile();
	}

	@Override public void onClick() {
		onTileClick();
	}

	static void setStatus(Context ctx, boolean wantRunning) {
		active = wantRunning;
		requestListeningState(ctx, new ComponentName(ctx, QuickToggleService.class));
	}

	private static native void onTileClick();
}
