// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import android.net.Uri
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.navigation.ExitNodesRoute
import com.tailscale.ipn.ui.navigation.MainRoute
import com.tailscale.ipn.ui.navigation.PeerDetailsRoute
import com.tailscale.ipn.ui.navigation.SettingsRoute
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog

// Deep links must only navigate — never perform an action on the user's
// behalf. Tails whose leaf would imply an action (e.g., a specific exit
// node) are accepted for URL compatibility but resolve to the parent
// view; the user picks and confirms in-app.
class DeepLinkNavigator(private val navController: NavHostController) {
  companion object {
    private const val TAG = "DeepLinkNavigator"
  }

  fun handle(uri: Uri): Boolean {
    if (uri.host != "navigate") return false
    val segments = uri.pathSegments
    val window = segments.firstOrNull() ?: return false
    val tail = segments.drop(1)

    return when (window) {
      "main" -> handleMain(tail)
      "settings" -> {
        showSettings()
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
            1 -> pushDeviceDetail(rest[0])
            else -> false
          }
      "exit-nodes" ->
          when {
            rest.size <= 1 || (rest.size == 2 && rest[0] == "location") -> {
              showExitNodePicker()
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

  private fun pushDeviceDetail(identifier: String): Boolean {
    val node = findNode(identifier)
    if (node == null) {
      TSLog.d(TAG, "Deep link: device not found for '$identifier'")
      return false
    }
    navigateOverMain(PeerDetailsRoute(node.StableID))
    return true
  }

  private fun findNode(identifier: String): Tailcfg.Node? {
    val netmap = Notifier.netmap.value ?: return null
    val all = netmap.Peers.orEmpty() + netmap.SelfNode
    return all.firstOrNull { identifier.equals(it.ComputedName, ignoreCase = true) }
        ?: all.firstOrNull { it.StableID == identifier }
  }

  private fun showExitNodePicker() {
    navigateOverMain(ExitNodesRoute)
  }

  private fun showSettings() {
    navigateOverMain(SettingsRoute)
  }

  private inline fun <reified T : Any> navigateOverMain(route: T) {
    navController.navigate(route) {
      popUpTo<MainRoute> { inclusive = false }
      launchSingleTop = true
    }
  }

  private fun popToMain() {
    if (navController.currentDestination?.hasRoute<MainRoute>() == true) {
      return
    }
    if (!navController.popBackStack<MainRoute>(inclusive = false)) {
      TSLog.d(TAG, "Deep link: failed to pop back stack to 'main'")
    }
  }
}
