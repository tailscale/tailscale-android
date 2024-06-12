// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.ui.util.AndroidTVUtil.isAndroidTV

object AndroidTVUtil {
  fun isAndroidTV(): Boolean {
    // Not ideal, but this is invoked from a Composable, at which point,
    // we *will* have an Uninitialized app instance.  If this method is being
    // called extremely early in the app lifecycle, you may get the wrong
    // answer here.
    val pm = UninitializedApp.get()?.packageManager ?: return false
    return (pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
        pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
  }
}

// Applies a letterbox effect iff we're running on Android TV to reduce the overall width
// of the UI.
fun Modifier.universalFit(): Modifier {
  return when (isAndroidTV()) {
    true -> this.padding(horizontal = 150.dp, vertical = 10.dp).clip(RoundedCornerShape(10.dp))
    false -> this
  }
}
