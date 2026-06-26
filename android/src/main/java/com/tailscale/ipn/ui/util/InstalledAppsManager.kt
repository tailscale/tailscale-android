// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import com.tailscale.ipn.BuildConfig
import java.text.Collator

data class InstalledApp(val name: String, val packageName: String)

class InstalledAppsManager(
    val packageManager: PackageManager,
) {
  private val iconCache =
      object : LruCache<String, Bitmap>(4 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
          return value.allocationByteCount / 1024
        }
      }

  fun fetchInstalledApps(): List<InstalledApp> {
    val collator = Collator.getInstance()
    return packageManager
        .getInstalledApplications(0)
        .filter(appIsIncluded)
        .mapNotNull { app ->
          runCatching {
                InstalledApp(
                    name = app.loadLabel(packageManager).toString(),
                    packageName = app.packageName,
                )
              }
              .getOrNull()
        }
        .sortedWith { left, right ->
          val nameComparison = collator.compare(left.name, right.name)
          if (nameComparison != 0) nameComparison else left.packageName.compareTo(right.packageName)
        }
  }

  fun iconForPackage(packageName: String, sizePx: Int): Bitmap {
    val cacheKey = "$packageName:$sizePx"
    iconCache.get(cacheKey)?.let {
      return it
    }

    val icon =
        runCatching { packageManager.getApplicationIcon(packageName) }
            .getOrElse { packageManager.defaultActivityIcon }
            .toBitmap(width = sizePx, height = sizePx, config = Bitmap.Config.ARGB_8888)
    iconCache.put(cacheKey, icon)
    return icon
  }

  private val appIsIncluded: (ApplicationInfo) -> Boolean = { app ->
    app.packageName != BuildConfig.APPLICATION_ID &&
        // Only show apps that can access the Internet
        packageManager.checkPermission(Manifest.permission.INTERNET, app.packageName) ==
            PackageManager.PERMISSION_GRANTED
  }
}
