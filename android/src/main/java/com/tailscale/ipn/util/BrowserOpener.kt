// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

object BrowserOpener {
  // Pin the ACTION_VIEW to the user's default browser package so non-browser
  // apps that also claim http(s) (e.g. the Google app) don't trigger the chooser.
  fun openInDefaultBrowser(context: Context, uri: Uri): Boolean {
    val intent =
        Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    defaultBrowserPackage(context)?.let { intent.setPackage(it) }
    return try {
      context.startActivity(intent)
      true
    } catch (_: Exception) {
      false
    }
  }

  private fun defaultBrowserPackage(context: Context): String? {
    val probe =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
    val ri =
        context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            ?: return null
    val pkg = ri.activityInfo?.packageName ?: return null
    // "android" / *.resolver means no default is set; let the chooser handle it.
    return if (pkg == "android" || pkg.contains("resolver", ignoreCase = true)) null else pkg
  }
}
