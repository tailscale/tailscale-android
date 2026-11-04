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
  fun login(scope: CoroutineScope, completionHandler: (Result<Unit>) -> Unit = {})
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

class AndroidLoginActionsDataSource :
    LoginActionsDataSource, AuthKeyLoginDataSource, CustomControlURLLoginDataSource {
  private val tag = "AndroidLoginActionsDataSource"

  override fun login(
      scope: CoroutineScope,
      completionHandler: (Result<Unit>) -> Unit,
  ) {
    performLogin(scope, maskedPrefs = null, authKey = null, completionHandler)
  }

  override fun loginWithAuthKey(
      scope: CoroutineScope,
      authKey: String,
      completionHandler: (Result<Unit>) -> Unit,
  ) {
    val prefs = Ipn.MaskedPrefs().apply { WantRunning = true }
    performLogin(scope, prefs, authKey, completionHandler)
  }

  override fun loginWithCustomControlURL(
      scope: CoroutineScope,
      controlURL: String,
      completionHandler: (Result<Unit>) -> Unit,
  ) {
    val prefs = Ipn.MaskedPrefs().apply { ControlURL = controlURL }
    performLogin(scope, prefs, authKey = null, completionHandler)
  }

  private fun performLogin(
      scope: CoroutineScope,
      maskedPrefs: Ipn.MaskedPrefs?,
      authKey: String?,
      completionHandler: (Result<Unit>) -> Unit,
  ) {
    UninitializedApp.get().startForegroundForLogin()
    val client = Client(scope)
    val finalMaskedPrefs = maskedPrefs?.deepCopy() ?: Ipn.MaskedPrefs()

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
