// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tailscale.ipn.ui.service.IpnManager
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.view.ExitNodePicker
import com.tailscale.ipn.ui.view.MainView
import com.tailscale.ipn.ui.view.MainViewNavigation
import com.tailscale.ipn.ui.view.PeerDetails
import com.tailscale.ipn.ui.view.Settings
import com.tailscale.ipn.ui.viewModel.MainViewModel


class MainActivity : ComponentActivity() {
    val model = IpnManager.getInstance().model
    private val viewModel = MainViewModel(model)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppTheme {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = "main") {
                val mainViewNav = MainViewNavigation(
                        onNavigateToSettings = { navController.navigate("settings") },
                        onNavigateToPeerDetails = {
                            navController.navigate("peerDetails/${it.StableID}")
                        },
                        onNavigateToExitNodes = { navController.navigate("exitNodes") }
                )

                composable("main") {
                    MainView(viewModel = viewModel, navigation = mainViewNav) }
                composable("settings") { Settings() }
                composable("exitNodes") { ExitNodePicker() }
                composable("peerDetails/{nodeId}",
                        arguments = listOf(navArgument("nodeId") { type = NavType.StringType })) { PeerDetails(it.arguments?.getString("nodeId")) }
            }
            }
        }
    }
}

