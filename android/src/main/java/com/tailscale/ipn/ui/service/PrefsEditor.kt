// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.service

import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn


// Handles all types of preference modifications typically invoked by the UI.
// Callers generally shouldn't care about the returned prefs value - the source of
// truth is the IPNModel, who's prefs flow will change in value to reflect the true
// value of the pref setting in the back end (and will match the value returned here).
// Generally, you will want to inspect the returned value in the callback for errors
// to indicate why a particular setting did not change in the interface.
//
// Usage:
// - User/Interface changed to new value.  Render the new value.
// - Submit the new value to the PrefsEditor
// - Observe the prefs on the IpnModel and update the UI when/if the value changes.
//   For a typical flow, the changed value should reflect the value already shown.
// - Inform the user of any error which may have occurred
//
// The "toggle' functions here will attempt to set the pref value to the inverse of
// what is currently known in the IpnModel.prefs.  If IpnModel.prefs is not available,
// the callback will be called with a NO_PREFS error

fun IpnModel.setWantRunning(wantRunning: Boolean, callback: (Result<Ipn.Prefs>) -> Unit) {
    Ipn.MaskedPrefs().WantRunning = wantRunning
    Client(scope).editPrefs(Ipn.MaskedPrefs(), callback)
}

fun IpnModel.toggleCorpDNS(callback: (Result<Ipn.Prefs>) -> Unit) {
    val prefs = prefs.value ?: run {
        callback(Result.failure(Exception("no prefs")))
        return@toggleCorpDNS
    }

    val prefsOut = Ipn.MaskedPrefs()
    prefsOut.CorpDNS = !prefs.CorpDNS
    Client(scope).editPrefs(prefsOut, callback)
}

fun IpnModel.toggleShieldsUp(callback: (Result<Ipn.Prefs>) -> Unit) {
    val prefs = prefs.value ?: run {
        callback(Result.failure(Exception("no prefs")))
        return@toggleShieldsUp
    }

    val prefsOut = Ipn.MaskedPrefs()
    prefsOut.ShieldsUp = !prefs.ShieldsUp
    Client(scope).editPrefs(prefsOut, callback)
}

fun IpnModel.setExitNodeId(id: String?, callback: (Result<Ipn.Prefs>) -> Unit) {
    val prefsOut = Ipn.MaskedPrefs()
    prefsOut.ExitNodeID = id
    Client(scope).editPrefs(prefsOut, callback)
}

fun IpnModel.toggleRouteAll(callback: (Result<Ipn.Prefs>) -> Unit) {
    val prefs = prefs.value ?: run {
        callback(Result.failure(Exception("no prefs")))
        return@toggleRouteAll
    }

    val prefsOut = Ipn.MaskedPrefs()
    prefsOut.RouteAll = !prefs.RouteAll
    Client(scope).editPrefs(prefsOut, callback)
}





