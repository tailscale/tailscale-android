// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.tailscale.ipn.util.TSLog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

class AdbTcpHttpTestWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

  override suspend fun doWork(): Result {
    val request = ProbeRequest.from(inputData)

    return runCatching {
          val response = App.get().getLibtailscaleApp().runTsocksProbe(Json.encodeToString(request))
          val json = JSONObject(response)
          Result.success(
              Data.Builder()
                  .putString("route", json.optString("route", "UNKNOWN"))
                  .putString("matchedRule", json.optString("matchedRule", "unknown"))
                  .putInt("bytesSent", json.optInt("bytesSent", 0))
                  .putInt("bytesReceived", json.optInt("bytesReceived", 0))
                  .putString("detail", json.optString("detail", ""))
                  .build())
        }
        .getOrElse { error ->
          TSLog.e(
              AdbTcpHttpTestContract.TAG_TEST,
              "event=TEST_FAIL requestId=${request.requestId} scenario=${request.scenario} route=UNKNOWN reason=${sanitize(error.message ?: error.javaClass.simpleName)}")
          Result.failure(
              Data.Builder().putString("reason", error.message ?: error.javaClass.simpleName).build())
        }
  }

  @Serializable
  private data class ProbeRequest(
      @SerialName("scenario") val scenario: String,
      @SerialName("requestId") val requestId: String,
      @SerialName("host") val host: String,
      @SerialName("port") val port: Int,
      @SerialName("protocol") val protocol: String,
      @SerialName("path") val path: String,
      @SerialName("payload") val payload: String,
      @SerialName("hostHeader") val hostHeader: String,
      @SerialName("timeoutMs") val timeoutMs: Int,
      @SerialName("socksEnabled") val socksEnabled: Boolean,
      @SerialName("previewOnly") val previewOnly: Boolean,
  ) {
    companion object {
      fun from(data: Data): ProbeRequest {
        return ProbeRequest(
            scenario = data.getString(AdbTcpHttpTestContract.EXTRA_SCENARIO)?.trim().orEmpty().ifEmpty { "unspecified" },
            requestId =
                data.getString(AdbTcpHttpTestContract.EXTRA_REQUEST_ID)?.trim().orEmpty().ifEmpty {
                  "req-${System.currentTimeMillis()}"
                },
            host = data.getString(AdbTcpHttpTestContract.EXTRA_HOST)?.trim().orEmpty(),
            port = data.getInt(AdbTcpHttpTestContract.EXTRA_PORT, -1),
            protocol =
                data.getString(AdbTcpHttpTestContract.EXTRA_PROTOCOL)
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()
                    .ifEmpty { AdbTcpHttpTestContract.DEFAULT_PROTOCOL },
            path = data.getString(AdbTcpHttpTestContract.EXTRA_PATH)?.trim().orEmpty(),
            payload = data.getString(AdbTcpHttpTestContract.EXTRA_PAYLOAD).orEmpty(),
            hostHeader = data.getString(AdbTcpHttpTestContract.EXTRA_HOST_HEADER)?.trim().orEmpty(),
            timeoutMs =
                data.getLong(
                        AdbTcpHttpTestContract.EXTRA_TIMEOUT_MS,
                        AdbTcpHttpTestContract.DEFAULT_TIMEOUT_MS)
                    .coerceIn(1L, 10_000L)
                    .toInt(),
            socksEnabled =
                data.getBoolean(
                    AdbTcpHttpTestContract.EXTRA_SOCKS_ENABLED,
                    AdbTcpHttpTestContract.DEFAULT_SOCKS_ENABLED),
            previewOnly = data.getBoolean(AdbTcpHttpTestContract.EXTRA_PREVIEW_ONLY, false),
        )
      }
    }
  }

  private fun sanitize(value: String): String {
    return value.replace(Regex("\\s+"), "_").replace(Regex("[^a-zA-Z0-9_./:=-]"), "-")
  }
}
