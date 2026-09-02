// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import org.junit.Assert.assertEquals
import org.junit.Test

class IPNServiceTest {
  @Test
  fun allowedPackagesIncludeTailscale() {
    val packages =
        packagesForVpnBuilder(
            packagesList = listOf("com.termux"),
            allowPackages = true,
            tailscalePackageName = "com.tailscale.ipn",
            builtInDisallowedPackages = emptyList())

    assertEquals(listOf("com.termux", "com.tailscale.ipn"), packages)
  }

  @Test
  fun excludedPackagesIncludeBuiltInDisallowedPackages() {
    val packages =
        packagesForVpnBuilder(
            packagesList = listOf("com.example.excluded"),
            allowPackages = false,
            tailscalePackageName = "com.tailscale.ipn",
            builtInDisallowedPackages = listOf("com.example.builtin"))

    assertEquals(listOf("com.example.excluded", "com.example.builtin"), packages)
  }
}
