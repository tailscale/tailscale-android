// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.net.VpnService
import libtailscale.ParcelFileDescriptor
import java.net.InetAddress
import android.net.IpPrefix as AndroidIpPrefix

class VPNServiceBuilder(private val builder: VpnService.Builder) : libtailscale.VPNServiceBuilder {
  override fun addAddress(p0: String, p1: Int) {
    builder.addAddress(p0, p1)
  }

  override fun addDNSServer(p0: String) {
    builder.addDnsServer(p0)
  }

  override fun addRoute(p0: String, p1: Int) {
    builder.addRoute(p0, p1)
  }

  override fun excludeRoute(p0: String, p1: Int) {
    val inetAddress = InetAddress.getByName(p0)
    val prefix = AndroidIpPrefix(inetAddress, p1)
    builder.excludeRoute(prefix)
  }

  override fun addSearchDomain(p0: String) {
    builder.addSearchDomain(p0)
  }

  override fun establish(): ParcelFileDescriptor? {
    return builder.establish()?.let { ParcelFileDescriptor(it) }
  }

  override fun setMTU(p0: Int) {
    builder.setMtu(p0)
  }
}

class ParcelFileDescriptor(private val fd: android.os.ParcelFileDescriptor) :
    libtailscale.ParcelFileDescriptor {
  override fun detach(): Int {
    return fd.detachFd()
  }
}
