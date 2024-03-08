// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.util

class DisplayAddress(val ip: String) {
    enum class addrType {
        V4, V6, MagicDNS
    }

    val type: addrType = when {
        ip.contains(":") -> addrType.V6
        ip.contains(".") -> addrType.V4
        else -> addrType.MagicDNS
    }

    val typeString: String = when (type) {
        addrType.V4 -> "IPv4"
        addrType.V6 -> "IPv6"
        addrType.MagicDNS -> "MagicDNS"
    }

    val address: String = when (type) {
        addrType.MagicDNS -> ip
        else -> ip.split("/").first()
    }
}