// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package androidvmtest

import (
	"net/url"
	"testing"
)

func logInToCustomControlServer(t *testing.T, e *androidEnv) {
	addAccountWithCustomServer(t, e)
	addAccountWithAuthKey(t, e)
	allowNotifications(t, e)
	waitForConnected(t, e)
	waitForNodeCount(t, e.control, 2)
	e.checkPeerTraffic(t)
	checkSettingsShowServer(t, e)
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

func checkSettingsShowServer(t *testing.T, e *androidEnv) {
	t.Helper()
	u, err := url.Parse(e.controlURL)
	if err != nil {
		t.Fatal(err)
	}
	openDeepLink(t, e, settingsDeepLink)
	waitForAppNode(t, e, "the account row showing "+u.Hostname(), withText(u.Hostname()))
}
