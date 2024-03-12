package com.tailscale.ipn.mdm

enum class BooleanSetting(val key: String, val localizedTitle: String) {
    ForceEnabled("ForceEnabled", "Force Enabled Connection Toggle")
}

enum class StringSetting(val key: String, val localizedTitle: String) {
    ExitNodeID("ExitNodeID", "Forced Exit Node: Stable ID"),
    KeyExpirationNotice("KeyExpirationNotice", "Key Expiration Notice Period"),
    LoginURL("LoginURL", "Custom control server URL"),
    ManagedByCaption("ManagedByCaption", "Managed By - Caption"),
    ManagedByOrganizationName("ManagedByOrganizationName", "Managed By - Organization Name"),
    ManagedByURL("ManagedByURL", "Managed By - Support URL"),
    Tailnet("Tailnet", "Recommended/Required Tailnet Name"),
}

enum class StringArraySetting(val key: String, val localizedTitle: String) {
    HiddenNetworkDevices("HiddenNetworkDevices", "Hidden Network Device Categories")
}

// A setting representing a String value which is set to either `always`, `never` or `user-decides`.
enum class AlwaysNeverUserDecidesSetting(val key: String, val localizedTitle: String) {
    AllowIncomingConnections("AllowIncomingConnections", "Allow Incoming Connections"),
    DetectThirdPartyAppConflicts("DetectThirdPartyAppConflicts", "Detect potentially problematic third-party apps"),
    ExitNodeAllowLANAccess("ExitNodeAllowLANAccess", "Allow LAN Access when using an exit node"),
    PostureChecking("PostureChecking", "Enable Posture Checking"),
    UseTailscaleDNSSettings("UseTailscaleDNSSettings", "Use Tailscale DNS Settings"),
    UseTailscaleSubnets("UseTailscaleSubnets", "Use Tailscale Subnets")
}

enum class AlwaysNeverUserDecidesValue(val value: String) {
    Always("always"),
    Never("never"),
    UserDecides("user-decides")
}

// A setting representing a String value which is set to either `show` or `hide`.
enum class ShowHideSetting(val key: String, val localizedTitle: String) {
    ExitNodesPicker("ExitNodesPicker", "Exit Nodes Picker"),
    ManageTailnetLock("ManageTailnetLock", "“Manage Tailnet lock” menu item"),
    ResetToDefaults("ResetToDefaults", "“Reset to Defaults” menu item"),
    RunExitNode("RunExitNode", "Run as Exit Node"),
    TestMenu("TestMenu", "Show Debug Menu"),
    UpdateMenu("UpdateMenu", "“Update Available” menu item"),
}

enum class ShowHideValue(val value: String) {
    Show("show"),
    Hide("hide")
}

enum class NetworkDevices(val value: String) {
    currentUser("current-user"),
    otherUsers("other-users"),
    taggedDevices("tagged-devices"),
}