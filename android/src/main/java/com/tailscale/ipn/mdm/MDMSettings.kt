// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.mdm

import android.content.Context
import android.content.RestrictionsManager
import android.content.SharedPreferences
import android.os.Bundle
import com.tailscale.ipn.App
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.jvm.jvmErasure

/**
 * The MDM policy settings the client understands, and the values currently in effect for each.
 *
 * Values normally come from the Android `RestrictionsManager`, which an MDM provider populates from
 * the policies declared in `res/xml/app_restrictions.xml`. Both the UI, which collects
 * [MDMSetting.flow] directly, and the Go backend, which reads these settings through the syspolicy
 * store in `App.getSyspolicyStringValue` and friends, see whatever is loaded here.
 *
 * ## Overriding policies in a debug build
 *
 * Debug builds can supply policies from a JSON file embedded in the APK instead, so that policy
 * behavior can be exercised without enrolling the device with an MDM provider. To use it:
 * 1. Copy `android/src/debug/assets/debug_policies.example.json` to
 *    `android/src/debug/assets/debug_policies.json`.
 * 2. Edit the `policies` object. Every key is a policy name declared in this file, such as
 *    `ForceEnabled` or `ExitNodeID`; the same names an MDM provider would use. The value's JSON
 *    type must match the setting's declared type:
 *     - [BooleanMDMSetting]: `true` or `false`.
 *     - [StringMDMSetting]: a string.
 *     - [StringArrayListMDMSetting]: an array of strings.
 *     - [AlwaysNeverUserDecidesMDMSetting]: `"always"`, `"never"`, or `"user-decides"`.
 *     - [ShowHideMDMSetting]: `"show"` or `"hide"`.
 *
 *   A `null` value, or omitting a key entirely, leaves that policy unset. Keys starting with an
 *   underscore are ignored, so they can be used as comments. Unknown keys and values of the wrong
 *   type are logged under the `syspolicy` tag and skipped.
 * 3. Rebuild and install. The policies are read at startup and again whenever restrictions are
 *    reloaded, so edits take effect on the next build, not the next launch.
 *
 * While the file is present and `"enabled": true`, it is the only source of policy: anything it
 * does not set reads as unset even if a real MDM provider has configured it. Set `"enabled"` to
 * `false` to fall back to real policies without deleting the file. Confirm what is actually in
 * effect under Settings > Current MDM Settings, which flags when these overrides are in use.
 *
 * `debug_policies.json` is gitignored; do not commit one. The whole mechanism lives in the debug
 * source set — see [DebugPolicies] — so neither the parser nor the policy file exists in a release
 * build.
 */
object MDMSettings {
  // The String message used in this NoSuchKeyException must match the value of
  // syspolicy.ErrNoSuchKey defined in Go. We compare against its exact text
  // to determine whether the requested policy setting is not configured and
  // an actual syspolicy.ErrNoSuchKey should be returned from syspolicyHandler
  // to the backend.
  class NoSuchKeyException : Exception("no such key")

  // MDM restriction keys
  const val KEY_HARDWARE_ATTESTATION = "HardwareAttestation"

  // We default this to true, so that stricter behavior is used during initialization,
  // prior to receiving MDM restrictions.
  var isMDMConfigured = true
    private set

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
          "DetectThirdPartyAppConflicts",
          "Detect potentially problematic third-party apps",
      )

  val exitNodeAllowLANAccess =
      AlwaysNeverUserDecidesMDMSetting(
          "ExitNodeAllowLANAccess",
          "Allow LAN Access when using an exit node",
      )

  // Handled on the backend
  val postureChecking =
      AlwaysNeverUserDecidesMDMSetting("PostureChecking", "Enable Posture Checking")

  // Handled on the backend
  val deviceSerialNumber =
      StringMDMSetting(
          "DeviceSerialNumber",
          "Serial number of the device that is running Tailscale",
      )

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

  val authKeyConnectAutomatically =
      BooleanMDMSetting("AuthKey.ConnectAutomatically", "Auth Key Connect Automatically")

  // Overrides the value provided by os.Hostname() in Go
  val hostname = StringMDMSetting("Hostname", "Device Hostname")

  // Allows admins to skip the get started intro screen
  val onboardingFlow = ShowHideMDMSetting("OnboardingFlow", "Suppress the intro screen")

  val allSettings by lazy {
    MDMSettings::class
        .declaredMemberProperties
        .filter {
          it.visibility == KVisibility.PUBLIC &&
              it.returnType.jvmErasure.isSubclassOf(MDMSetting::class)
        }
        .map { it.call(MDMSettings) as MDMSetting<*> }
  }

  val hardwareAttestation =
      BooleanMDMSetting(
          KEY_HARDWARE_ATTESTATION,
          "Use hardware-backed keys to bind node identity to the device",
      )

  val allSettingsByKey by lazy { allSettings.associateBy { it.key } }

  /**
   * Returns the restrictions to load policies from: the developer-defined overrides described above
   * if a debug build has any, otherwise the restrictions set by the MDM provider.
   */
  fun resolveRestrictions(context: Context, restrictionsManager: RestrictionsManager?): Bundle? =
      DebugPolicies.overrideBundle(context) ?: restrictionsManager?.applicationRestrictions

  fun loadFrom(preferences: Lazy<SharedPreferences>, restrictions: Bundle?) {
    allSettings.forEach { it.setFrom(restrictions, preferences) }
    isMDMConfigured = restrictions != null && !restrictions.isEmpty
  }

  fun update(app: App, restrictionsManager: RestrictionsManager?) {
    loadFrom(lazy { app.getEncryptedPrefs() }, resolveRestrictions(app, restrictionsManager))
    app.notifyPolicyChanged()
  }
}
