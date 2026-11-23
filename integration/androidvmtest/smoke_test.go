// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package androidvmtest

import "testing"

func TestAndroidSmoke(t *testing.T) {
	e := newAndroidEnv(t)
	requireNoDebugPolicies(t)
	e.resetApp(t)
	prepareDeviceForUI(t, e.adb)
	disableBrowser(t, e)

	var launched, loggedIn bool
	t.Run("App launches", func(t *testing.T) {
		recordScreen(t, e.adb)
		launchApp(t, e)
		completeFirstRun(t, e)
		launched = true
	})
	t.Run("Log in to a custom control server", func(t *testing.T) {
		if !launched {
			t.Skip("needs App launches to pass")
		}
		recordScreen(t, e.adb)
		addAccountWithCustomServer(t, e)
		addAccountWithAuthKey(t, e)
		allowNotifications(t, e)
		waitForConnected(t, e)
		waitForNodeCount(t, e.control, 2)
		e.checkPeerTraffic(t)
		checkSettingsShowServer(t, e)
		loggedIn = true
	})
	t.Run("Taildrop Send", func(t *testing.T) {
		if !loggedIn {
			t.Skip("needs Log in to a custom control server to pass")
		}
		recordScreen(t, e.adb)
		name, content := taildropFile(t, "send", 4<<10)
		shareFromProbe(t, e, name, content)
		waitForPeerFile(t, e, name)
		e.checkPeerFile(t, name, []byte(content))
	})
	t.Run("Taildrop receive", func(t *testing.T) {
		if !loggedIn {
			t.Skip("needs Log in to a custom control server to pass")
		}
		recordScreen(t, e.adb)
		showMainScreen(t, e)
		waitForConnected(t, e)
		name, content := taildropFile(t, "receive", 32<<10)
		sent := e.peerSendsFile(t, name, []byte(content))
		pickTaildropFolder(t, e)
		if err := <-sent; err != nil {
			t.Fatalf("tsnet peer PushFile: %v", err)
		}
		checkDeviceFile(t, e, "/sdcard/Documents/"+name, content)
	})
}
