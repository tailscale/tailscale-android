// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import android.net.Uri
import androidx.navigation.NavHostController
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope

// URL shape (mirrors iOS / macOS):
//   tailscale://navigate/main/devices
//   tailscale://navigate/main/devices/<computedName|stableID>
//   tailscale://navigate/main/exit-nodes
//   tailscale://navigate/main/exit-nodes/<stableID>
//   tailscale://navigate/main/exit-nodes/location/<country>
//   tailscale://navigate/settings[/<subRoute>]
class DeepLinkNavigator(
    private val navController: NavHostController,
    private val scope: CoroutineScope,
) {
  companion object {
    private const val TAG = "DeepLinkNavigator"
    private val settingsSubRoutes =
        setOf(
            "about",
            "bugReport",
            "dnsSettings",
            "splitTunneling",
            "tailnetLock",
            "subnetRouting",
            "mdmSettings",
            "managedBy",
            "userSwitcher",
            "permissions",
        )
  }

  fun handle(uri: Uri): Boolean {
    if (uri.host != "navigate") return false
    val segments = uri.pathSegments
    val window = segments.firstOrNull() ?: return false
    val tail = segments.drop(1)

    return when (window) {
      "main" -> handleMain(tail)
      "settings" -> {
        presentSettings(tail.firstOrNull()?.takeIf { it in settingsSubRoutes })
        true
      }
      else -> false
    }
  }

  private fun handleMain(tail: List<String>): Boolean {
    val tab = tail.firstOrNull() ?: return false
    val rest = tail.drop(1)

    return when (tab) {
      "devices" ->
          when (rest.size) {
            0 -> {
              showDeviceList()
              true
            }
            1 -> {
              pushDeviceDetail(rest[0])
              true
            }
            else -> false
          }
      "exit-nodes" ->
          when {
            rest.isEmpty() -> {
              presentExitNodePicker()
              true
            }
            rest.size == 1 -> {
              selectExitNode(rest[0])
              true
            }
            rest.size == 2 && rest[0] == "location" -> {
              selectExitNodeCountry(rest[1])
              true
            }
            else -> false
          }
      else -> false
    }
  }

  private fun showDeviceList() {
    popToMain()
  }

  private fun pushDeviceDetail(identifier: String) {
    val node = findNode(identifier)
    if (node == null) {
      TSLog.d(TAG, "Deep link: device not found for '$identifier'")
      return
    }
    popToMain()
    navController.navigate("peerDetails/${node.StableID}")
  }

  // Matches macOS: ComputedName (case-insensitive) first, then StableID.
  private fun findNode(identifier: String): Tailcfg.Node? {
    val netmap = Notifier.netmap.value ?: return null
    val all = netmap.Peers.orEmpty() + netmap.SelfNode
    return all.firstOrNull { identifier.equals(it.ComputedName, ignoreCase = true) }
        ?: all.firstOrNull { it.StableID == identifier }
  }

  private fun presentExitNodePicker() {
    popToMain()
    navController.navigate("exitNodes")
  }

  private fun selectExitNode(identifier: String) {
    val peers = Notifier.netmap.value?.Peers.orEmpty()
    val match = peers.firstOrNull { it.StableID == identifier && it.isExitNode }
    if (match == null) {
      TSLog.d(TAG, "Deep link: exit node not found for '$identifier'")
      presentExitNodePicker()
      return
    }
    val prefs = Ipn.MaskedPrefs()
    prefs.ExitNodeID = match.StableID
    Client(scope).editPrefs(prefs) { result ->
      result.onFailure { TSLog.e(TAG, "Deep link: editPrefs failed", it) }
    }
  }

  // Mirrors iOS: open the picker rather than threading a country-specific destination.
  private fun selectExitNodeCountry(country: String) {
    presentExitNodePicker()
    TSLog.d(TAG, "Deep link: requested exit-node country '$country' (presenting picker)")
  }

  private fun presentSettings(subRoute: String? = null) {
    popToMain()
    navController.navigate("settings")
    subRoute?.let { navController.navigate(it) }
  }

  private fun popToMain() {
    navController.popBackStack(route = "main", inclusive = false)
  }
}
