// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package androidvmtest

import (
	"archive/zip"
	"strings"
	"testing"
)

const (
	vpnDialogsPackage = "com.android.vpndialogs"
	chromePackage     = "com.android.chrome"
	settingsDesc      = "Open settings"
	accountsMenuDesc  = "menu"
	settingsDeepLink  = "tailscale://navigate/settings"
	mainDeepLink      = "tailscale://navigate/main/devices"
)

func isVPNConsentOK(n uiNode) bool {
	return n.Package == vpnDialogsPackage && n.ResourceID == "android:id/button1"
}

func isCheckedSwitch(n uiNode) bool {
	return n.Checkable && n.Checked
}

func requireNoDebugPolicies(t *testing.T) {
	t.Helper()
	r, err := zip.OpenReader(*apkPath)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	for _, f := range r.File {
		if f.Name == "assets/debug_policies.json" {
			t.Fatalf("%s embeds assets/debug_policies.json, which skips the first-run screens; rebuild it without that file", *apkPath)
		}
	}
}

// disableBrowser stops the login page the app opens after VPN consent from taking the test's taps.
func disableBrowser(t *testing.T, e *androidEnv) {
	t.Helper()
	if out, err := e.adb.runOutputAllowError("shell", "pm", "disable-user", "--user", "0", chromePackage); err != nil {
		t.Logf("pm disable-user %s: %v\n%s", chromePackage, err, out)
	}
	if out := e.adb.shellOutput(t, "cmd", "package", "query-activities", "-a", "android.intent.action.VIEW", "-d", "https://login.tailscale.com/"); !strings.Contains(out, "No activities found") {
		t.Fatalf("an app can still open the login page over the test:\n%s", out)
	}
}

func openDeepLink(t *testing.T, e *androidEnv, link string) {
	t.Helper()
	e.adb.startActivity(t, mainActivity, "-a", "android.intent.action.VIEW", "-d", link)
}

// showMainScreen uses a deep link, since launching a running app leaves it on its last screen.
func showMainScreen(t *testing.T, e *androidEnv) {
	t.Helper()
	openDeepLink(t, e, mainDeepLink)
}

func dismissForeignApp(t *testing.T, e *androidEnv, pkg string) {
	t.Helper()
	if pkg == vpnDialogsPackage || strings.HasSuffix(pkg, ".permissioncontroller") {
		return
	}
	t.Logf("%s is in front of %s, pressing BACK", pkg, androidPackage)
	e.adb.shell(t, "input", "keyevent", "KEYCODE_BACK")
}

// waitForAppNode presses BACK while another app, such as a login browser, is in front.
func waitForAppNode(t *testing.T, e *androidEnv, what string, match func(uiNode) bool) uiNode {
	t.Helper()
	var found uiNode
	pollUI(t, e.adb, what, func(s uiScreen) bool {
		if s.pkg() != androidPackage {
			dismissForeignApp(t, e, s.pkg())
			return false
		}
		n, ok := s.find(match)
		found = n
		return ok
	})
	return found
}

func waitForConnected(t *testing.T, e *androidEnv) {
	t.Helper()
	connected := appString(t, strConnected)
	pollUI(t, e.adb, "the VPN switch on with the Connected status", func(s uiScreen) bool {
		if s.pkg() != androidPackage {
			dismissForeignApp(t, e, s.pkg())
			return false
		}
		return s.has(withText(connected)) && s.has(isCheckedSwitch)
	})
}
