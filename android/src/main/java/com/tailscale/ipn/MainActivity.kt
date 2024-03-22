// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.RestrictionsManager
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navigation
import com.tailscale.ipn.Peer.RequestCodes
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.view.AboutView
import com.tailscale.ipn.ui.view.BugReportView
import com.tailscale.ipn.ui.view.ExitNodePicker
import com.tailscale.ipn.ui.view.MDMSettingsDebugView
import com.tailscale.ipn.ui.view.MainView
import com.tailscale.ipn.ui.view.MainViewNavigation
import com.tailscale.ipn.ui.view.ManagedByView
import com.tailscale.ipn.ui.view.MullvadExitNodePicker
import com.tailscale.ipn.ui.view.PeerDetails
import com.tailscale.ipn.ui.view.Settings
import com.tailscale.ipn.ui.view.UserSwitcherView
import com.tailscale.ipn.ui.viewModel.ExitNodePickerNav
import com.tailscale.ipn.ui.viewModel.IpnViewModel
import com.tailscale.ipn.ui.viewModel.SettingsNav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private var notifierScope: CoroutineScope? = null
  private lateinit var requestVpnPermission: ActivityResultLauncher<Unit>

  companion object {
    // Request codes for Android callbacks.
    // requestSignin is for Google Sign-In.
    @JvmStatic val requestSignin: Int = 1000
    // requestPrepareVPN is for when Android's VpnService.prepare completes.
    @JvmStatic val requestPrepareVPN: Int = 1001
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContent {
      AppTheme {
        val navController = rememberNavController()
        NavHost(navController = navController, startDestination = "main") {
          val mainViewNav =
              MainViewNavigation(
                  onNavigateToSettings = { navController.navigate("settings") },
                  onNavigateToPeerDetails = {
                    navController.navigate("peerDetails/${it.StableID}")
                  },
                  onNavigateToExitNodes = { navController.navigate("exitNodes") },
              )

          val settingsNav =
              SettingsNav(
                  onNavigateToBugReport = { navController.navigate("bugReport") },
                  onNavigateToAbout = { navController.navigate("about") },
                  onNavigateToMDMSettings = { navController.navigate("mdmSettings") },
                  onNavigateToManagedBy = { navController.navigate("managedBy") },
                  onNavigateToUserSwitcher = { navController.navigate("userSwitcher") },
              )

          val exitNodePickerNav =
              ExitNodePickerNav(
                  onNavigateHome = {
                    navController.popBackStack(route = "main", inclusive = false)
                  },
                  onNavigateToMullvadCountry = { navController.navigate("mullvad/$it") })

          composable("main") { MainView(navigation = mainViewNav) }
          composable("settings") { Settings(settingsNav) }
          navigation(startDestination = "list", route = "exitNodes") {
            composable("list") { ExitNodePicker(exitNodePickerNav) }
            composable(
                "mullvad/{countryCode}",
                arguments = listOf(navArgument("countryCode") { type = NavType.StringType })) {
                  MullvadExitNodePicker(
                      it.arguments!!.getString("countryCode")!!, exitNodePickerNav)
                }
          }
          composable(
              "peerDetails/{nodeId}",
              arguments = listOf(navArgument("nodeId") { type = NavType.StringType })) {
                PeerDetails(it.arguments?.getString("nodeId") ?: "")
              }
          composable("bugReport") { BugReportView() }
          composable("about") { AboutView() }
          composable("mdmSettings") { MDMSettingsDebugView() }
          composable("managedBy") { ManagedByView() }
          composable("userSwitcher") { UserSwitcherView() }
        }
      }
    }
    lifecycleScope.launch {
      Notifier.readyToPrepareVPN.collect { isReady ->
        if (isReady)
            App.getApplication().prepareVPN(this@MainActivity, RequestCodes.requestPrepareVPN)
      }
    }
  }

  init {
    // Watch the model's browseToURL and launch the browser when it changes
    // This will trigger the login flow
    lifecycleScope.launch {
      Notifier.browseToURL.collect { url -> url?.let { Dispatchers.Main.run { login(it) } } }
    }
  }

  private fun login(url: String) {
    // (jonathan) TODO: This is functional, but the navigation doesn't quite work
    // as expected.  There's probably a better built in way to do this.  This will
    // unblock in dev for the time being though.
    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    startActivity(browserIntent)
  }

  override fun onResume() {
    super.onResume()
    val restrictionsManager =
        this.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    IpnViewModel.mdmSettings.set(MDMSettings(restrictionsManager))
  }

  override fun onStart() {
    super.onStart()
    val scope = CoroutineScope(Dispatchers.IO)
    notifierScope = scope
    Notifier.start(lifecycleScope)

    // (jonathan) TODO: Requesting VPN permissions onStart is a bit aggressive.  This should
    // be done when the user initiall starts the VPN
    requestVpnPermission()
  }

  override fun onStop() {
    Notifier.stop()
    super.onStop()
    val restrictionsManager =
        this.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    IpnViewModel.mdmSettings.set(MDMSettings(restrictionsManager))
  }

  private fun requestVpnPermission() {
    val vpnIntent = VpnService.prepare(this)
    if (vpnIntent != null) {
      val contract = VpnPermissionContract()
      requestVpnPermission =
          registerForActivityResult(contract) { granted ->
            Notifier.vpnPermissionGranted.set(granted)
            Log.i("VPN", "VPN permission ${if (granted) "granted" else "denied"}")
          }
      requestVpnPermission.launch(Unit)
    } else {
      Notifier.vpnPermissionGranted.set(true)
      Log.i("VPN", "VPN permission granted")
    }
  }
}

class VpnPermissionContract : ActivityResultContract<Unit, Boolean>() {
  override fun createIntent(context: Context, input: Unit): Intent {
    return VpnService.prepare(context) ?: Intent()
  }

  override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
    return resultCode == Activity.RESULT_OK
  }
}
