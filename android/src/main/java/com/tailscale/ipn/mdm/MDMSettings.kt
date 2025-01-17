// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.mdm

import android.content.RestrictionsManager
import com.tailscale.ipn.App
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.jvm.jvmErasure

object MDMSettings {
  // The String message used in this NoSuchKeyException must match the value of
  // syspolicy.ErrNoSuchKey defined in Go. We compare against its exact text
  // to determine whether the requested policy setting is not configured and
  // an actual syspolicy.ErrNoSuchKey should be returned from syspolicyHandler
  // to the backend.
  class NoSuchKeyException : Exception("no such key")

  val forceEnabled = BooleanMDMSetting("ForceEnabled", "Force Enabled Connection Toggle")

  // Handled on the backed
  val exitNodeID = StringMDMSetting("ExitNodeID", "Forced Exit Node: Stable ID")

  // (jonathan) TODO: Unused but required. There is some funky go string duration parsing required
  // here.
  val keyExpirationNotice = StringMDMSetting("KeyExpirationNotice", "Key Expiration Notice Period")

  val loginURL = StringMDMSetting("LoginURL", "Custom control server URL")

  val managedByCaption = StringMDMSetting("ManagedByCaption", "Managed By - Caption")

  val managedByOrganizationName =
      StringMDMSetting("ManagedByOrganizationName", "Managed By - Organization Name")

  val managedByURL = StringMDMSetting("ManagedByURL", "Managed By - Support URL")

  // Handled on the backend
  val tailnet = StringMDMSetting("Tailnet", "Recommended/Required Tailnet Name")

  val hiddenNetworkDevices =
      StringArrayListMDMSetting("HiddenNetworkDevices", "Hidden Network Device Categories")

  // Unused on Android
  val allowIncomingConnections =
      AlwaysNeverUserDecidesMDMSetting("AllowIncomingConnections", "Allow Incoming Connections")

  // Unused on Android
  val detectThirdPartyAppConflicts =
      AlwaysNeverUserDecidesMDMSetting(
          "DetectThirdPartyAppConflicts", "Detect potentially problematic third-party apps")

  val exitNodeAllowLANAccess =
      AlwaysNeverUserDecidesMDMSetting(
          "ExitNodeAllowLANAccess", "Allow LAN Access when using an exit node")

  // Handled on the backend
  val postureChecking =
      AlwaysNeverUserDecidesMDMSetting("PostureChecking", "Enable Posture Checking")

  val useTailscaleDNSSettings =
      AlwaysNeverUserDecidesMDMSetting("UseTailscaleDNSSettings", "Use Tailscale DNS Settings")

  // Unused on Android
  val useTailscaleSubnets =
      AlwaysNeverUserDecidesMDMSetting("UseTailscaleSubnets", "Use Tailscale Subnets")

  val exitNodesPicker = ShowHideMDMSetting("ExitNodesPicker", "Exit Nodes Picker")

  val manageTailnetLock = ShowHideMDMSetting("ManageTailnetLock", "“Manage Tailnet lock” menu item")

  // Unused on Android
  val resetToDefaults = ShowHideMDMSetting("ResetToDefaults", "“Reset to Defaults” menu item")

  val runExitNode = ShowHideMDMSetting("RunExitNode", "Run as Exit Node")

  // Unused on Android
  val testMenu = ShowHideMDMSetting("TestMenu", "Show Debug Menu")

  // Unused on Android
  val updateMenu = ShowHideMDMSetting("UpdateMenu", "“Update Available” menu item")

  // (jonathan) TODO: Use this when suggested exit nodes are implemented
  val allowedSuggestedExitNodes =
      StringArrayListMDMSetting("AllowedSuggestedExitNodes", "Allowed Suggested Exit Nodes")

  // Allows admins to define a list of packages that won't be routed via Tailscale.
  val excludedPackages = StringMDMSetting("ExcludedPackageNames", "Excluded Package Names")
  // Allows admins to define a list of packages that will be routed via Tailscale, letting all other
  // apps skip the VPN tunnel.
  val includedPackages = StringMDMSetting("IncludedPackageNames", "Included Package Names")

  // Handled on the backend
  val authKey = StringMDMSetting("AuthKey", "Auth Key for login")

  // Overrides the value provided by os.Hostname() in Go
  val hostname = StringMDMSetting("Hostname", "Device Hostname")

  val allSettings by lazy {
    MDMSettings::class
        .declaredMemberProperties
        .filter {
          it.visibility == KVisibility.PUBLIC &&
              it.returnType.jvmErasure.isSubclassOf(MDMSetting::class)
        }
        .map { it.call(MDMSettings) as MDMSetting<*> }
  }

  val allSettingsByKey by lazy { allSettings.associateBy { it.key } }

  fun update(app: App, restrictionsManager: RestrictionsManager?) {
    val bundle = restrictionsManager?.applicationRestrictions
    val preferences = lazy { app.getEncryptedPrefs() }
    allSettings.forEach { it.setFrom(bundle, preferences) }
    app.notifyPolicyChanged()
  }
}
