// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package androidvmtest

import (
	"archive/zip"
	"net/url"
	"strings"
	"testing"
	"time"
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

func launchApp(t *testing.T, e *androidEnv) {
	t.Helper()
	e.adb.startActivity(t, mainActivity)
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

func completeFirstRun(t *testing.T, e *androidEnv) {
	t.Helper()
	getStarted := appString(t, strGetStarted)
	loginToJoin := appString(t, strLoginToJoin)
	var tappedGetStarted, acceptedVPN bool
	pollUI(t, e.adb, "the logged-out main screen", func(s uiScreen) bool {
		if n, ok := s.find(isVPNConsentOK); ok {
			if !acceptedVPN {
				tap(t, e.adb, n)
				acceptedVPN = true
				t.Logf("accepted the VPN consent dialog")
			}
			return false
		}
		if s.pkg() != androidPackage {
			dismissForeignApp(t, e, s.pkg())
			return false
		}
		if n, ok := s.find(withText(getStarted)); ok && !tappedGetStarted {
			tap(t, e.adb, n)
			tappedGetStarted = true
			return false
		}
		return s.has(withText(loginToJoin))
	})
	if !acceptedVPN {
		t.Fatal("reached the logged-out main screen without the VPN consent dialog; is ACTIVATE_VPN still allowed?")
	}
}

func openAccountsMenuItem(t *testing.T, e *androidEnv, item string) {
	t.Helper()
	tap(t, e.adb, waitForAppNode(t, e, "the settings button", withDesc(settingsDesc)))
	tap(t, e.adb, waitForAppNode(t, e, "the Accounts row", withText(appString(t, strAccounts))))
	tap(t, e.adb, waitForAppNode(t, e, "the Accounts menu", withDesc(accountsMenuDesc)))
	tap(t, e.adb, waitForAppNode(t, e, item, withText(item)))
}

func addAccount(t *testing.T, e *androidEnv, menuItem, screenText, value string) {
	t.Helper()
	openAccountsMenuItem(t, e, appString(t, menuItem))
	text := appString(t, screenText)
	waitForAppNode(t, e, text, withText(text))
	typeInto(t, e.adb, waitForAppNode(t, e, "the text field", withClass(editTextClass)), value)
	tap(t, e.adb, waitForAppNode(t, e, "the Add account button", withText(appString(t, strAddAccount))))
}

func addAccountWithCustomServer(t *testing.T, e *androidEnv) {
	t.Helper()
	addAccount(t, e, strCustomControlMenu, strCustomControlDesc, e.controlURL)
	loginToJoin := appString(t, strLoginToJoin)
	failed := appString(t, strAddProfileFailed)
	n := waitForAppNode(t, e, "the main screen after adding the server", func(n uiNode) bool {
		return n.Text == loginToJoin || n.Text == failed
	})
	if n.Text == failed {
		t.Fatalf("adding %s showed %q", e.controlURL, failed)
	}
}

func addAccountWithAuthKey(t *testing.T, e *androidEnv) {
	t.Helper()
	addAccount(t, e, strAuthKeyMenu, strAuthKeyExplanation, authKey)
}

func allowNotifications(t *testing.T, e *androidEnv) {
	t.Helper()
	tap(t, e.adb, waitForAppNode(t, e, "the notifications prompt", withText(appString(t, strContinue))))
	tap(t, e.adb, waitForNode(t, e.adb, "the notifications permission dialog", withResourceIDSuffix(":id/permission_allow_button")))
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

func checkSettingsShowServer(t *testing.T, e *androidEnv) {
	t.Helper()
	u, err := url.Parse(e.controlURL)
	if err != nil {
		t.Fatal(err)
	}
	openDeepLink(t, e, settingsDeepLink)
	waitForAppNode(t, e, "the account row showing "+u.Hostname(), withText(u.Hostname()))
}

func shareFromProbe(t *testing.T, e *androidEnv, name, content string) {
	t.Helper()
	tailscale := appString(t, strAppName)
	shareTitle := appString(t, strShare)
	e.adb.startActivity(t, probePackage+"/.ShareFileActivity", "--es", "name", name, "--es", "content", content)
	var polls, tappedAt, misses int
	pollUI(t, e.adb, "the tsnet peer on Tailscale's share screen", func(s uiScreen) bool {
		polls++
		switch pkg := s.pkg(); {
		case pkg == androidPackage && s.has(withText(shareTitle)):
			if n, ok := s.find(withText(peerHostname)); ok {
				tap(t, e.adb, n)
				return true
			}
		case pkg == "android" || strings.HasSuffix(pkg, ".intentresolver"):
			if n, ok := s.find(withText(tailscale)); ok {
				// A second tap could land on the share screen as it opens, so wait a few polls first.
				if tappedAt == 0 || polls-tappedAt >= 4 {
					tap(t, e.adb, n)
					tappedAt = polls
				}
				return false
			}
			if misses++; misses%3 == 0 {
				swipeUp(t, e.adb)
			}
		}
		return false
	})
}

func waitForPeerFile(t *testing.T, e *androidEnv, name string) {
	t.Helper()
	failures := []string{appString(t, strTaildropShareFailedTitle), appString(t, strTaildropShareFailedShort)}
	pollUI(t, e.adb, "the tsnet peer to receive "+name, func(s uiScreen) bool {
		for _, f := range failures {
			if s.has(withText(f)) {
				t.Fatalf("Tailscale's share screen shows %q", f)
			}
		}
		return e.peerHasFile(t, name)
	})
}

func pickTaildropFolder(t *testing.T, e *androidEnv) {
	t.Helper()
	saved := appString(t, strTaildropSavedInDirectory)
	steps := []func(uiNode) bool{
		withText(appString(t, strTaildropDirPickerButton)),
		withTextFold("Use this folder"),
		withTextPrefixFold("Allow access to"),
		withTextFold("Allow"),
		withTextFold("Documents"),
	}
	pollUIFor(t, e.adb, "the Taildrop folder picked and the received-file banner", 3*time.Minute, func(s uiScreen) bool {
		if s.pkg() == androidPackage && s.has(withText(saved)) {
			return true
		}
		for _, match := range steps {
			if n, ok := s.find(func(n uiNode) bool { return n.Enabled && match(n) }); ok {
				tap(t, e.adb, n)
				return false
			}
		}
		return false
	})
	waitForNode(t, e.adb, "the banner title", withText(strings.ToUpper(appString(t, strTaildropFileReceived))))
}
