// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

class DisplayAddress(ip: String) {
  enum class addrType {
    V4,
    V6,
    MagicDNS
  }

  val type: addrType =
      when {
        ip.isIPV6() -> addrType.V6
        ip.isIPV4() -> addrType.V4
        else -> addrType.MagicDNS
      }

  val typeString: String =
      when (type) {
        addrType.V4 -> "IPv4"
        addrType.V6 -> "IPv6"
        addrType.MagicDNS -> "MagicDNS"
      }

  val address: String =
      when (type) {
        addrType.MagicDNS -> ip
        else -> ip.split("/").first()
      }
}

fun String.isIPV6(): Boolean {
  return this.contains(":")
}

fun String.isIPV4(): Boolean {
  val parts = this.split("/").first().split(".")
  if (parts.size != 4) return false
  for (part in parts) {
    val value = part.toIntOrNull() ?: return false
    if (value !in 0..255) return false
  }
  return true
}
