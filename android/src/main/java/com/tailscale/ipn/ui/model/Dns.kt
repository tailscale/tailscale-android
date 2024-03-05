// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.model

import kotlinx.serialization.*

class Dns {
    @Serializable data class HostEntry(val addr: Addr?, val hosts: List<String>?)

    @Serializable
    data class OSConfig(
            val hosts: List<HostEntry>? = null,
            val nameservers: List<Addr>? = null,
            val searchDomains: List<String>? = null,
            val matchDomains: List<String>? = null,
    ) {
        val isEmpty: Boolean
            get() = (hosts.isNullOrEmpty()) &&
                    (nameservers.isNullOrEmpty()) &&
                    (searchDomains.isNullOrEmpty()) &&
                    (matchDomains.isNullOrEmpty())
    }
}

class DnsType {
    @Serializable
    data class Resolver(var Addr: String? = null, var BootstrapResolution: List<Addr>? = null)
}
