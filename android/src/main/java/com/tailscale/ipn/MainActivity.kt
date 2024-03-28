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
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import com.tailscale.ipn.ui.view.AboutView
import com.tailscale.ipn.ui.view.BackNavigation
import com.tailscale.ipn.ui.view.BugReportView
import com.tailscale.ipn.ui.view.DNSSettingsView
import com.tailscale.ipn.ui.view.ExitNodePicker
import com.tailscale.ipn.ui.view.IntroView
import com.tailscale.ipn.ui.view.MDMSettingsDebugView
import com.tailscale.ipn.ui.view.MainView
import com.tailscale.ipn.ui.view.MainViewNavigation
import com.tailscale.ipn.ui.view.ManagedByView
import com.tailscale.ipn.ui.view.MullvadExitNodePicker
import com.tailscale.ipn.ui.view.MullvadExitNodePickerList
import com.tailscale.ipn.ui.view.PeerDetails
import com.tailscale.ipn.ui.view.PermissionsView
import com.tailscale.ipn.ui.view.RunExitNodeView
import com.tailscale.ipn.ui.view.SettingsView
import com.tailscale.ipn.ui.view.TailnetLockSetupView
import com.tailscale.ipn.ui.view.UserSwitcherView
import com.tailscale.ipn.ui.viewModel.ExitNodePickerNav
import com.tailscale.ipn.ui.viewModel.SettingsNav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private var notifierScope: CoroutineScope? = null
  private lateinit var requestVpnPermission: ActivityResultLauncher<Unit>

  companion object {
    // Request codes for Android callbacks.
    // requestPrepareVPN is for when Android's VpnService.prepare completes.
    @JvmStatic val requestPrepareVPN: Int = 1001

    const val WRITE_STORAGE_RESULT = 1000
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContent {
      AppTheme {
        val navController = rememberNavController()
        NavHost(
            navController = navController,
            startDestination = "main",
            enterTransition = {
              slideInHorizontally(animationSpec = tween(150), initialOffsetX = { it })
            },
            exitTransition = {
              slideOutHorizontally(animationSpec = tween(150), targetOffsetX = { -it })
            },
            popEnterTransition = {
              slideInHorizontally(animationSpec = tween(150), initialOffsetX = { -it })
            },
            popExitTransition = {
              slideOutHorizontally(animationSpec = tween(150), targetOffsetX = { it })
            }) {
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
                      onNavigateToDNSSettings = { navController.navigate("dnsSettings") },
                      onNavigateToTailnetLock = { navController.navigate("tailnetLock") },
                      onNavigateToMDMSettings = { navController.navigate("mdmSettings") },
                      onNavigateToManagedBy = { navController.navigate("managedBy") },
                      onNavigateToUserSwitcher = { navController.navigate("userSwitcher") },
                      onNavigateToPermissions = { navController.navigate("permissions") },
                      onBackPressed = { navController.popBackStack() },
                  )

              val backNav = BackNavigation(onBack = { navController.popBackStack() })

              val exitNodePickerNav =
                  ExitNodePickerNav(
                      onNavigateHome = {
                        navController.popBackStack(route = "main", inclusive = false)
                      },
                      onNavigateBack = { navController.popBackStack() },
                      onNavigateToExitNodePicker = { navController.popBackStack() },
                      onNavigateToMullvad = { navController.navigate("mullvad") },
                      onNavigateToMullvadCountry = { navController.navigate("mullvad/$it") },
                      onNavigateToRunAsExitNode = { navController.navigate("runExitNode") })

              composable("main") { MainView(navigation = mainViewNav) }
              composable("settings") { SettingsView(settingsNav) }
              navigation(startDestination = "list", route = "exitNodes") {
                composable("list") { ExitNodePicker(exitNodePickerNav) }
                composable("mullvad") { MullvadExitNodePickerList(exitNodePickerNav) }
                composable(
                    "mullvad/{countryCode}",
                    arguments = listOf(navArgument("countryCode") { type = NavType.StringType })) {
                      MullvadExitNodePicker(
                          it.arguments!!.getString("countryCode")!!, exitNodePickerNav)
                    }
                composable("runExitNode") { RunExitNodeView(exitNodePickerNav) }
              }
              composable(
                  "peerDetails/{nodeId}",
                  arguments = listOf(navArgument("nodeId") { type = NavType.StringType })) {
                    PeerDetails(nav = backNav, it.arguments?.getString("nodeId") ?: "")
                  }
              composable("bugReport") { BugReportView(nav = backNav) }
              composable("dnsSettings") { DNSSettingsView(nav = backNav) }
              composable("tailnetLock") { TailnetLockSetupView(nav = backNav) }
              composable("about") { AboutView(nav = backNav) }
              composable("mdmSettings") { MDMSettingsDebugView(nav = backNav) }
              composable("managedBy") { ManagedByView(nav = backNav) }
              composable("userSwitcher") {
                UserSwitcherView(
                    nav = backNav,
                    onNavigateHome = {
                      navController.popBackStack(route = "main", inclusive = false)
                    })
              }
              composable("permissions") {
                PermissionsView(nav = backNav, openApplicationSettings = ::openApplicationSettings)
              }
              composable("intro") { IntroView { navController.popBackStack() } }
            }

        // Show the intro screen one time
        if (!introScreenViewed()) {
          navController.navigate("intro")
          setIntroScreenViewed(true)
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
    lifecycleScope.launch(Dispatchers.IO) {
      MDMSettings.update(App.getApplication(), restrictionsManager)
    }
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
    lifecycleScope.launch(Dispatchers.IO) {
      MDMSettings.update(App.getApplication(), restrictionsManager)
    }
  }

  private fun requestVpnPermission() {
    val vpnIntent = VpnService.prepare(this)
    if (vpnIntent != null) {
      val contract = VpnPermissionContract()
      requestVpnPermission =
          registerForActivityResult(contract) { granted ->
            Log.i("VPN", "VPN permission ${if (granted) "granted" else "denied"}")
          }
      requestVpnPermission.launch(Unit)
    }
  }

  private fun openApplicationSettings() {
    val intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
  }

  private fun introScreenViewed(): Boolean {
    return getSharedPreferences("introScreen", Context.MODE_PRIVATE).getBoolean("seen", false)
  }

  private fun setIntroScreenViewed(seen: Boolean) {
    getSharedPreferences("introScreen", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("seen", seen)
        .apply()
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
