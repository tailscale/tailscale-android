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

	var launched, loggedIn, magicDNS, usingExitNode bool
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
	dnsIP := e.startDNSPeer(t)
	t.Run("MagicDNS can add multiple devices", func(t *testing.T) {
		if !loggedIn {
			t.Skip("needs Log in to a custom control server to pass")
		}
		recordScreen(t, e.adb)
		magicDNSCanAddMultipleDevices(t, e)
		magicDNS = true
	})
	t.Run("Magic DNS device reachable via device hostname in browser", func(t *testing.T) {
		if !magicDNS {
			t.Skip("needs MagicDNS can add multiple devices to pass")
		}
		recordScreen(t, e.adb)
		magicDNSDeviceReachableViaHostname(t, e)
	})
	t.Run("SplitDNS can be configured", func(t *testing.T) {
		if !loggedIn {
			t.Skip("needs Log in to a custom control server to pass")
		}
		recordScreen(t, e.adb)
		splitDNSCanBeConfigured(t, e, dnsIP)
	})
	t.Run("Override Global DNS", func(t *testing.T) {
		if !loggedIn {
			t.Skip("needs Log in to a custom control server to pass")
		}
		recordScreen(t, e.adb)
		overrideGlobalDNS(t, e, dnsIP)
	})
	e.control.SetDNSConfig(nil)
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
}
