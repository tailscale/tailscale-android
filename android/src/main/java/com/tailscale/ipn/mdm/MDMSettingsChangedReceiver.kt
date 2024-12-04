// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.mdm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.RestrictionsManager
import com.tailscale.ipn.App
import com.tailscale.ipn.util.TSLog

class MDMSettingsChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED) {
            TSLog.d("syspolicy", "MDM settings changed")
            val restrictionsManager = context?.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
            MDMSettings.update(App.get(), restrictionsManager)
        }
    }
}