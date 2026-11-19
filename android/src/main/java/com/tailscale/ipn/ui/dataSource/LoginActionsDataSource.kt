// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.dataSource

import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.deepCopy
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope

interface LoginActionsDataSource {
  fun login(
      scope: CoroutineScope,
      maskedPrefs: Ipn.MaskedPrefs? = null,
      authKey: String? = null,
      completionHandler: (Result<Unit>) -> Unit = {},
  )
}

interface AuthKeyLoginDataSource {
  fun loginWithAuthKey(
      scope: CoroutineScope,
      authKey: String,
      completionHandler: (Result<Unit>) -> Unit = {},
  )
}

interface CustomControlURLLoginDataSource {
  fun loginWithCustomControlURL(
      scope: CoroutineScope,
      controlURL: String,
      completionHandler: (Result<Unit>) -> Unit = {},
  )
}

class LoginActionsDataSourceImpl :
    LoginActionsDataSource, AuthKeyLoginDataSource, CustomControlURLLoginDataSource {
  private val tag = "AndroidLoginActionsDataSource"

  override fun loginWithAuthKey(
      scope: CoroutineScope,
      authKey: String,
      completionHandler: (Result<Unit>) -> Unit,
  ) {
    val prefs = Ipn.MaskedPrefs().apply { WantRunning = true }
    login(scope, prefs, authKey, completionHandler)
  }

  override fun loginWithCustomControlURL(
      scope: CoroutineScope,
      controlURL: String,
      completionHandler: (Result<Unit>) -> Unit,
  ) {
    val prefs = Ipn.MaskedPrefs().apply { ControlURL = controlURL }
    login(scope, prefs, authKey = null, completionHandler)
  }

  /**
   * Order of operations:
   * 1. editPrefs() with maskedPrefs (to allow ControlURL override), LoggedOut=false if AuthKey !=
   *    null
   * 2. start() starts the LocalBackend state machine with WantRunning=true. to avoid a race: if
   *    editPrefs() set WantRunning=true, the backend would fire a cc.Login(LoginDefault) on the
   *    existing control client immediately. start() then tears down this client and creates a new
   *    one
   * 3. startLoginInteractive() is currently required for both interactive and non-interactive
   *    (using auth key) login
   *
   * Any failure short‑circuits the chain and invokes completionHandler once.
   */
  override fun login(
      scope: CoroutineScope,
      maskedPrefs: Ipn.MaskedPrefs?,
      authKey: String?,
      completionHandler: (Result<Unit>) -> Unit,
  ) {
    // Start the IPNService foreground notification so that Android
    // does not freeze the process or cut network access while the user is in the browser
    // completing auth. The foreground service transitions to a full VPN service later when
    // startVPN() is called after the backend reaches Running state.
    UninitializedApp.get().startForegroundForLogin()
    val client = Client(scope)

    val finalMaskedPrefs = maskedPrefs?.deepCopy() ?: Ipn.MaskedPrefs()

    // Don't set WantRunning=true here. Setting it in editPrefs() triggers cc.Login(LoginDefault)
    // in the Go backend on the existing control client; when the user taps "Log in," login() calls
    // start(), which triggers resetControlClientLocked(), cancelling the existing control client
    // and creating a new one. On a slow connection, the first control client's register is still
    // in flight when it is canceled. Instead, set WantRunning=true on the Prefs returned by
    // editPrefs() and pass
    // it via start()'s UpdatePrefs, which resets the control client first.
    if (authKey != null) {
      finalMaskedPrefs.LoggedOut = false
    }

    client.editPrefs(finalMaskedPrefs) { editResult ->
      editResult
          .onFailure {
            TSLog.e(tag, "editPrefs() failed: ${it.message}")
            completionHandler(Result.failure(it))
          }
          .onSuccess { prefs ->
            prefs.WantRunning = true
            val options = Ipn.Options(UpdatePrefs = prefs, AuthKey = authKey)
            client.start(options) { startResult ->
              startResult
                  .onFailure {
                    TSLog.e(tag, "start() failed: ${it.message}")
                    completionHandler(Result.failure(it))
                  }
                  .onSuccess {
                    client.startLoginInteractive { loginResult ->
                      loginResult
                          .onFailure {
                            TSLog.e(tag, "startLoginInteractive() failed: ${it.message}")
                            completionHandler(Result.failure(it))
                          }
                          .onSuccess { completionHandler(Result.success(Unit)) }
                    }
                  }
            }
          }
    }
  }
}
