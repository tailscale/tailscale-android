// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.tailscale.ipn.util.TSLog
import java.net.InetAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@Suppress("DEPRECATION")
class NetworkRefreshTest {
  private val connectivityManager = mock<ConnectivityManager>()
  private val dns = mock<DnsConfig>()
  private lateinit var originalLogWrapper: TSLog.LibtailscaleWrapper

  @Before
  fun setUp() {
    originalLogWrapper = TSLog.libtailscaleWrapper
    TSLog.libtailscaleWrapper = mock()
    whenever(connectivityManager.allNetworks).thenReturn(emptyArray())
    NetworkChangeCallback.setUnderlyingNetworkListener(null)
    NetworkChangeCallback.refreshDefaultNetwork(connectivityManager, dns)
  }

  @After
  fun tearDown() {
    NetworkChangeCallback.setUnderlyingNetworkListener(null)
    whenever(connectivityManager.allNetworks).thenReturn(emptyArray())
    NetworkChangeCallback.refreshDefaultNetwork(connectivityManager, dns)
    TSLog.libtailscaleWrapper = originalLogWrapper
  }

  @Test
  fun reconnectReplacesLostNetworkWithoutWaitingForCallbacks() {
    val oldWifi = network("wlan0")
    whenever(connectivityManager.allNetworks).thenReturn(arrayOf(oldWifi))
    NetworkChangeCallback.refreshDefaultNetwork(connectivityManager, dns)
    assertSame(oldWifi, NetworkChangeCallback.cachedDefaultNetwork)

    // The network handle changes while the process survives. Interface and DNS stay the same.
    val newWifi = network("wlan0")
    whenever(connectivityManager.allNetworks).thenReturn(arrayOf(newWifi))
    val underlyingUpdates = mutableListOf<Network?>()
    NetworkChangeCallback.setUnderlyingNetworkListener { underlyingUpdates.add(it) }

    NetworkChangeCallback.refreshDefaultNetwork(connectivityManager, dns)

    assertSame(newWifi, NetworkChangeCallback.cachedDefaultNetwork)
    assertEquals("wlan0", NetworkChangeCallback.cachedDefaultInterfaceName)
    assertEquals(listOf(newWifi), underlyingUpdates)
  }

  @Test
  fun reconnectClearsStaleNetworkWhenOffline() {
    val wifi = network("wlan0")
    whenever(connectivityManager.allNetworks).thenReturn(arrayOf(wifi))
    NetworkChangeCallback.refreshDefaultNetwork(connectivityManager, dns)

    whenever(connectivityManager.allNetworks).thenReturn(emptyArray())
    val underlyingUpdates = mutableListOf<Network?>()
    NetworkChangeCallback.setUnderlyingNetworkListener { underlyingUpdates.add(it) }

    NetworkChangeCallback.refreshDefaultNetwork(connectivityManager, dns)

    assertNull(NetworkChangeCallback.cachedDefaultNetwork)
    assertNull(NetworkChangeCallback.cachedDefaultInterfaceName)
    assertEquals(listOf<Network?>(null), underlyingUpdates)
  }

  @Test
  fun reconnectDoesNotSelectTheVpnAsItsOwnUnderlyingNetwork() {
    val vpn = network("tun0", notVpn = false)
    val wifi = network("wlan0")
    whenever(connectivityManager.allNetworks).thenReturn(arrayOf(vpn, wifi))

    NetworkChangeCallback.refreshDefaultNetwork(connectivityManager, dns)

    assertSame(wifi, NetworkChangeCallback.cachedDefaultNetwork)
  }

  @Test
  fun reconnectDiscardsNetworksThatDisappearDuringTheSnapshot() {
    val wifi = network("wlan0")
    whenever(connectivityManager.allNetworks).thenReturn(arrayOf(wifi))
    NetworkChangeCallback.refreshDefaultNetwork(connectivityManager, dns)

    whenever(connectivityManager.getNetworkCapabilities(wifi)).thenReturn(null)
    NetworkChangeCallback.refreshDefaultNetwork(connectivityManager, dns)

    assertNull(NetworkChangeCallback.cachedDefaultNetwork)
  }

  @Test
  fun reconnectRefreshesDnsFromTheSelectedNetwork() {
    val oldDns = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
    val newDns = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2))
    val oldWifi = network("wlan0", dnsServers = listOf(oldDns))
    whenever(connectivityManager.allNetworks).thenReturn(arrayOf(oldWifi))
    NetworkChangeCallback.refreshDefaultNetwork(connectivityManager, dns)

    val newWifi = network("wlan0", dnsServers = listOf(newDns))
    whenever(connectivityManager.allNetworks).thenReturn(arrayOf(newWifi))

    NetworkChangeCallback.refreshDefaultNetwork(connectivityManager, dns)

    verify(dns).updateDNSFromNetwork("${newDns.hostAddress} ")
  }

  @Test
  fun reconnectWaitsForLinkPropertiesBeforeSelectingANewNetwork() {
    val oldWifi = network("wlan0")
    whenever(connectivityManager.allNetworks).thenReturn(arrayOf(oldWifi))
    NetworkChangeCallback.refreshDefaultNetwork(connectivityManager, dns)

    val newWifi = network("wlan0")
    whenever(connectivityManager.allNetworks).thenReturn(arrayOf(newWifi))
    whenever(connectivityManager.getLinkProperties(newWifi)).thenReturn(null)

    NetworkChangeCallback.refreshDefaultNetwork(connectivityManager, dns)

    assertNull(NetworkChangeCallback.cachedDefaultNetwork)
    assertNull(NetworkChangeCallback.cachedDefaultInterfaceName)
  }

  private fun network(
      iface: String,
      notVpn: Boolean = true,
      dnsServers: List<InetAddress> = listOf(InetAddress.getLoopbackAddress()),
  ): Network {
    val network = mock<Network>()
    val caps = mock<NetworkCapabilities>()
    whenever(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
    whenever(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)).thenReturn(notVpn)
    whenever(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
    whenever(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).thenReturn(true)
    val linkProps = mock<LinkProperties>()
    whenever(linkProps.interfaceName).thenReturn(iface)
    whenever(linkProps.dnsServers).thenReturn(dnsServers)
    whenever(connectivityManager.getNetworkCapabilities(network)).thenReturn(caps)
    whenever(connectivityManager.getLinkProperties(network)).thenReturn(linkProps)
    return network
  }
}
