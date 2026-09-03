// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkChangeCallbackTest {
  @Test
  fun prefersValidatedNetworkWithDns() {
    val result =
        pickPreferredNetwork(
            listOf(
                candidate("unvalidated-dns", validated = false, hasDns = true),
                candidate("validated-no-dns", validated = true, hasDns = false),
                candidate("validated-dns", validated = true, hasDns = true),
            ))

    assertEquals("validated-dns", result)
  }

  @Test
  fun prefersValidatedNetworkWithoutDnsOverUnvalidatedNetworkWithDns() {
    val result =
        pickPreferredNetwork(
            listOf(
                candidate("unvalidated-dns", validated = false, hasDns = true),
                candidate("validated-no-dns", validated = true, hasDns = false),
            ))

    assertEquals("validated-no-dns", result)
  }

  @Test
  fun fallsBackToUnvalidatedNetworkWithDns() {
    val result =
        pickPreferredNetwork(
            listOf(
                candidate("unvalidated-no-dns", validated = false, hasDns = false),
                candidate("unvalidated-dns", validated = false, hasDns = true),
            ))

    assertEquals("unvalidated-dns", result)
  }

  @Test
  fun fallsBackToUnvalidatedNetworkWithoutDns() {
    val result =
        pickPreferredNetwork(listOf(candidate("unvalidated", validated = false, hasDns = false)))

    assertEquals("unvalidated", result)
  }

  @Test
  fun prefersNonMeteredNetworkWithinSameTier() {
    val result =
        pickPreferredNetwork(
            listOf(
                candidate("metered", nonMetered = false),
                candidate("non-metered", nonMetered = true),
            ))

    assertEquals("non-metered", result)
  }

  @Test
  fun ignoresNetworksWithoutInternet() {
    val result =
        pickPreferredNetwork(
            listOf(
                candidate("no-internet", internet = false),
                candidate("internet"),
            ))

    assertEquals("internet", result)
  }

  @Test
  fun ignoresVpnNetworks() {
    val result =
        pickPreferredNetwork(
            listOf(
                candidate("vpn", notVpn = false),
                candidate("non-vpn"),
            ))

    assertEquals("non-vpn", result)
  }

  @Test
  fun returnsNullWhenNoUsableNetworkExists() {
    val result =
        pickPreferredNetwork(
            listOf(
                candidate("no-internet", internet = false),
                candidate("vpn", notVpn = false),
            ))

    assertNull(result)
  }

  @Test
  fun networkCanBecomePreferredWhenItBecomesValidated() {
    val cellular = candidate("cellular", validated = true, nonMetered = false)
    val wifi = candidate("wifi", validated = false, nonMetered = true)

    assertEquals("cellular", pickPreferredNetwork(listOf(cellular, wifi)))

    val validatedWifi = wifi.copy(validated = true)

    assertEquals("wifi", pickPreferredNetwork(listOf(cellular, validatedWifi)))
  }

  private fun candidate(
      name: String,
      internet: Boolean = true,
      notVpn: Boolean = true,
      validated: Boolean = true,
      hasDns: Boolean = true,
      nonMetered: Boolean = false,
  ) =
      NetworkCandidate(
          value = name,
          internet = internet,
          notVpn = notVpn,
          validated = validated,
          hasDns = hasDns,
          nonMetered = nonMetered,
      )
}
