// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.autoconnect

import android.content.Context

object TrustedNetworks {
  private const val PREFS_NAME = "tailscale_auto"
  private const val KEY_SSIDS = "trusted_ssids"
  private const val KEY_ENABLED = "auto_vpn_enabled"

  fun isEnabled(ctx: Context): Boolean =
      ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          .getBoolean(KEY_ENABLED, false)

  fun setEnabled(ctx: Context, enabled: Boolean) =
      ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          .edit()
          .putBoolean(KEY_ENABLED, enabled)
          .apply()

  fun load(ctx: Context): Set<String> =
      ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          .getStringSet(KEY_SSIDS, emptySet()) ?: emptySet()

  fun save(ctx: Context, ssids: Set<String>) =
      ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          .edit()
          .putStringSet(KEY_SSIDS, ssids)
          .apply()

  fun add(ctx: Context, ssid: String) = save(ctx, load(ctx) + ssid)

  fun remove(ctx: Context, ssid: String) = save(ctx, load(ctx) - ssid)
}
