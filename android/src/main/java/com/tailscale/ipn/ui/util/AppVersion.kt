// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import com.tailscale.ipn.BuildConfig

class AppVersion {
  companion object {
    // Returns the short version of the build version, which is what users typically expect.
    // For instance, if the build version is "1.75.80-t8fdffb8da-g2daeee584df",
    // this function returns "1.75.80".
    fun Short(): String {
      // Split the full version string by hyphen (-)
      val parts = BuildConfig.VERSION_NAME.split("-")
      // Return only the part before the first hyphen
      return parts[0]
    }
  }
}
