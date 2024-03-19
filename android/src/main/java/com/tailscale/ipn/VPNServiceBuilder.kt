package com.tailscale.ipn

import android.net.VpnService
import libtailscale.ParcelFileDescriptor

class VPNServiceBuilder(private val builder: VpnService.Builder) : VPNServiceBuilder {
  override fun addAddress(p0: String, p1: Int) {
    builder.addAddress(p0, p1)
  }

  override fun addDNSServer(p0: String) {
    builder.addDnsServer(p0)
  }

  override fun addRoute(p0: String, p1: Int) {
    builder.addRoute(p0, p1)
  }

  override fun addSearchDomain(p0: String) {
    builder.addSearchDomain(p0)
  }

  override fun establish(): ParcelFileDescriptor? {
    return builder.establish()?.let { ParcelFileDescriptor(it) }
  }

  override fun setMTU(p0: Long) {
    TODO("Not yet implemented")
  }
}

class ParcelFileDescriptor(private val fd: android.os.ParcelFileDescriptor) :
    libtailscale.ParcelFileDescriptor {
  override fun detach(): Int {
    return fd.detachFd()
  }
}
