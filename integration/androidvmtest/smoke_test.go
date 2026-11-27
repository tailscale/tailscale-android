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
	browser := e.startBrowserTailnet(t)

	var launched, loggedIn, usingExitNode, loggedOut bool
	var firstAccount string
	t.Run("App launches", func(t *testing.T) {
		recordScreen(t, e.adb)
		appLaunches(t, e)
		launched = true
	})
	t.Run("Log in to a custom control server", func(t *testing.T) {
		if !launched {
			t.Skip("needs App launches to pass")
		}
		recordScreen(t, e.adb)
		logInToCustomControlServer(t, e)
		loggedIn = true
	})
	t.Run("Taildrop Send", func(t *testing.T) {
		if !loggedIn {
			t.Skip("needs Log in to a custom control server to pass")
		}
		recordScreen(t, e.adb)
		taildropSend(t, e)
	})
	t.Run("Taildrop receive", func(t *testing.T) {
		if !loggedIn {
			t.Skip("needs Log in to a custom control server to pass")
		}
		recordScreen(t, e.adb)
		taildropReceive(t, e)
	})
	t.Run("Exit node can be enabled", func(t *testing.T) {
		if !loggedIn {
			t.Skip("needs Log in to a custom control server to pass")
		}
		recordScreen(t, e.adb)
		exitNodeCanBeEnabled(t, e)
		usingExitNode = true
	})
	t.Run("Do not allow device access to its local network when routing traffic through an exit node", func(t *testing.T) {
		if !usingExitNode {
			t.Skip("needs Exit node can be enabled to pass")
		}
		recordScreen(t, e.adb)
		exitNodeLANAccess(t, e)
	})
	t.Run("Log Out", func(t *testing.T) {
		if !loggedIn {
			t.Skip("needs Log in to a custom control server to pass")
		}
		recordScreen(t, e.adb)
		logOut(t, e)
		loggedOut = true
	})
	t.Run("Log In", func(t *testing.T) {
		if !loggedOut {
			t.Skip("needs Log Out to pass")
		}
		recordScreen(t, e.adb)
		firstAccount = logIn(t, e, browser)
	})
	t.Run("Fast User Switching", func(t *testing.T) {
		if firstAccount == "" {
			t.Skip("needs Log In to pass")
		}
		recordScreen(t, e.adb)
		fastUserSwitching(t, e, browser, firstAccount)
	})
}
