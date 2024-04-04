// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

// The package identifiers for applications which are known to break when Tailscale is enabled.
object DisallowedApps {
  val apps =
      arrayOf(
          // RCS/Jibe https://github.com/tailscale/tailscale/issues/2322
          "com.google.android.apps.messaging",
          // Stadia https://github.com/tailscale/tailscale/issues/3460
          "com.google.stadia.android",
          // Android Auto https://github.com/tailscale/tailscale/issues/3828
          "com.google.android.projection.gearhead",
          // GoPro https://github.com/tailscale/tailscale/issues/2554
          "com.gopro.smarty",
          // Sonos https://github.com/tailscale/tailscale/issues/2548
          "com.sonos.acr",
          "com.sonos.acr2",
          // Google Chromecast https://github.com/tailscale/tailscale/issues/3636
          "com.google.android.apps.chromecast.app")
}
