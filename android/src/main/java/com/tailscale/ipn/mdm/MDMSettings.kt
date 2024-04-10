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
  val forceEnabled = BooleanMDMSetting("ForceEnabled", "Force Enabled Connection Toggle")

  val exitNodeID = StringMDMSetting("ExitNodeID", "Forced Exit Node: Stable ID")
  val keyExpirationNotice = StringMDMSetting("KeyExpirationNotice", "Key Expiration Notice Period")
  val loginURL = StringMDMSetting("LoginURL", "Custom control server URL")
  val managedByCaption = StringMDMSetting("ManagedByCaption", "Managed By - Caption")
  val managedByOrganizationName =
      StringMDMSetting("ManagedByOrganizationName", "Managed By - Organization Name")
  val managedByURL = StringMDMSetting("ManagedByURL", "Managed By - Support URL")
  val tailnet = StringMDMSetting("Tailnet", "Recommended/Required Tailnet Name")

  val hiddenNetworkDevices =
      StringArrayListMDMSetting("HiddenNetworkDevices", "Hidden Network Device Categories")

  val allowIncomingConnections =
      AlwaysNeverUserDecidesMDMSetting("AllowIncomingConnections", "Allow Incoming Connections")
  val detectThirdPartyAppConflicts =
      AlwaysNeverUserDecidesMDMSetting(
          "DetectThirdPartyAppConflicts", "Detect potentially problematic third-party apps")
  val exitNodeAllowLANAccess =
      AlwaysNeverUserDecidesMDMSetting(
          "ExitNodeAllowLANAccess", "Allow LAN Access when using an exit node")
  val postureChecking =
      AlwaysNeverUserDecidesMDMSetting("PostureChecking", "Enable Posture Checking")
  val useTailscaleDNSSettings =
      AlwaysNeverUserDecidesMDMSetting("UseTailscaleDNSSettings", "Use Tailscale DNS Settings")
  val useTailscaleSubnets =
      AlwaysNeverUserDecidesMDMSetting("UseTailscaleSubnets", "Use Tailscale Subnets")

  val exitNodesPicker = ShowHideMDMSetting("ExitNodesPicker", "Exit Nodes Picker")
  val manageTailnetLock = ShowHideMDMSetting("ManageTailnetLock", "“Manage Tailnet lock” menu item")
  val resetToDefaults = ShowHideMDMSetting("ResetToDefaults", "“Reset to Defaults” menu item")
  val runExitNode = ShowHideMDMSetting("RunExitNode", "Run as Exit Node")
  val testMenu = ShowHideMDMSetting("TestMenu", "Show Debug Menu")
  val updateMenu = ShowHideMDMSetting("UpdateMenu", "“Update Available” menu item")

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
    allSettings.forEach { it.setFrom(bundle, app) }
  }
}
