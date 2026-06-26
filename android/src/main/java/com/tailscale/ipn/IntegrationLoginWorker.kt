// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CompletableDeferred

/**
 * Test-only worker used by emulator integration tests to log a debug APK into a local testcontrol
 * server with an auth key.
 *
 * IPNReceiver only enqueues this worker when BuildConfig.DEBUG is true. Release builds do not
 * expose the broadcast entry point, and minified final APKs strip this unreachable debug-only path.
 */
class IntegrationLoginWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    if (!BuildConfig.DEBUG) {
      return Result.failure()
    }

    val controlURL = inputData.getString(EXTRA_CONTROL_URL)
    val authKey = inputData.getString(EXTRA_AUTH_KEY)
    if (controlURL.isNullOrBlank() || authKey.isNullOrBlank()) {
      TSLog.e(TAG, "missing $EXTRA_CONTROL_URL or $EXTRA_AUTH_KEY")
      return Result.failure()
    }

    return try {
      login(controlURL, authKey)
      Result.success()
    } catch (e: Exception) {
      TSLog.e(TAG, "integration login failed: $e")
      Result.failure()
    }
  }

  private suspend fun login(controlURL: String, authKey: String) {
    val app = App.get()
    app.startForegroundForLogin()

    val client = Client(app.applicationScope)
    val maskedPrefs =
        Ipn.MaskedPrefs().apply {
          ControlURL = controlURL
          LoggedOut = false
        }

    val prefs = await<Ipn.Prefs> { client.editPrefs(maskedPrefs, it) }.getOrThrow()
    prefs.WantRunning = true

    val opts = Ipn.Options(UpdatePrefs = prefs, AuthKey = authKey)
    await<Unit> { client.start(opts, it) }.getOrThrow()
    await<Unit> { client.startLoginInteractive(it) }.getOrThrow()
    app.startVPN()
  }

  private suspend fun <T> await(call: ((kotlin.Result<T>) -> Unit) -> Unit): kotlin.Result<T> {
    val result = CompletableDeferred<kotlin.Result<T>>()
    call { result.complete(it) }
    return result.await()
  }

  companion object {
    const val TAG = "IntegrationLoginWorker"
    const val WORK_NAME = "ipn-integration-login"
    const val EXTRA_CONTROL_URL = "control_url"
    const val EXTRA_AUTH_KEY = "auth_key"
  }
}
