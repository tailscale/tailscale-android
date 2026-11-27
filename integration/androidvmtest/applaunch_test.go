// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package androidvmtest

import "testing"

func appLaunches(t *testing.T, e *androidEnv) {
	launchApp(t, e)
	completeFirstRun(t, e)
}

func launchApp(t *testing.T, e *androidEnv) {
	t.Helper()
	e.adb.startActivity(t, mainActivity)
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
