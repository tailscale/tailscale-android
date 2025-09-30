// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Activity to enable the Tailscale VPN via app shortcut.
 * Sends a broadcast to IPNReceiver and finishes immediately.
 */
public class EnableVpnShortcutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent broadcastIntent = new Intent(IPNReceiver.INTENT_CONNECT_VPN);
        broadcastIntent.setClass(this, IPNReceiver.class);
        sendBroadcast(broadcastIntent);

        finish();  // Close the activity immediately
    }
}
