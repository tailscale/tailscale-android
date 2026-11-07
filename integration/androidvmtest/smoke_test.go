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

	var launched bool
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
	})
}
