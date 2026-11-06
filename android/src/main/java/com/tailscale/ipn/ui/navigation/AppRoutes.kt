// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object MainRoute

@Serializable data object SearchRoute

@Serializable data object SettingsRoute

@Serializable data object ExitNodesRoute

@Serializable data object HealthRoute

@Serializable data object MullvadRoute

@Serializable data object MullvadInfoRoute

@Serializable data class MullvadCountryRoute(val countryCode: String)

@Serializable data object RunExitNodeRoute

@Serializable data class PeerDetailsRoute(val nodeId: String)

@Serializable data object BugReportRoute

@Serializable data object DNSSettingsRoute

@Serializable data object SplitTunnelingRoute

@Serializable data object TailnetLockRoute

@Serializable data object SubnetRoutingRoute

@Serializable data object AboutRoute

@Serializable data object MDMSettingsRoute

@Serializable data object ManagedByRoute

@Serializable data object UserSwitcherRoute

@Serializable data object PermissionsRoute

@Serializable data object TaildropDirRoute

@Serializable data object NotificationsRoute

@Serializable data object IntroRoute

@Serializable data object LoginWithAuthKeyRoute

@Serializable data object LoginWithCustomControlRoute
