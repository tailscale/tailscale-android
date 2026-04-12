// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.app.Activity
import android.os.Bundle
import com.tailscale.ipn.util.TSLog
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DatapathTestActivity : Activity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (!BuildConfig.DEBUG) {
      finish()
      return
    }

    val scenario = intent.getStringExtra(AdbTcpHttpTestContract.EXTRA_SCENARIO)?.trim().orEmpty()
    val requestId =
        intent.getStringExtra(AdbTcpHttpTestContract.EXTRA_REQUEST_ID)?.trim().orEmpty().ifEmpty {
          "req-${System.currentTimeMillis()}"
        }
    val url = intent.getStringExtra(AdbTcpHttpTestContract.EXTRA_URL)?.trim().orEmpty()
    val timeoutMs =
        intent.getLongExtra(
                AdbTcpHttpTestContract.EXTRA_TIMEOUT_MS, AdbTcpHttpTestContract.DEFAULT_TIMEOUT_MS)
            .coerceIn(1L, 10_000L)
            .toInt()

    scope.launch {
      if (url.isEmpty()) {
        TSLog.e(
            AdbTcpHttpTestContract.TAG_TEST,
            "event=TEST_FAIL requestId=$requestId scenario=$scenario route=DATAPATH reason=missing_url")
        finish()
        return@launch
      }

      TSLog.d(
          AdbTcpHttpTestContract.TAG_TEST,
          "event=request_start requestId=$requestId scenario=$scenario protocol=http url=${sanitize(url)} flow=datapath-client")

      val result =
          withContext(Dispatchers.IO) {
            runCatching {
              val uri = URI(url)
              val host = uri.host ?: throw IllegalArgumentException("missing_host")
              val port = if (uri.port == -1) 80 else uri.port
              val path = if (uri.rawPath.isNullOrBlank()) "/" else uri.rawPath
              val socket = Socket()
              socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
              socket.soTimeout = timeoutMs
              val request =
                  buildString {
                        append("GET ")
                        append(path)
                        append(" HTTP/1.1\r\n")
                        append("Host: ")
                        append(host)
                        append("\r\nConnection: close\r\n")
                        append("User-Agent: tailscale-android-tsocks-datapath-test\r\n\r\n")
                      }
                      .toByteArray(StandardCharsets.UTF_8)
              socket.getOutputStream().write(request)
              socket.getOutputStream().flush()
              val response = socket.getInputStream().readBytes()
              socket.close()
              val statusLine = response.toString(StandardCharsets.UTF_8).lineSequence().firstOrNull()?.trim().orEmpty()
              val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
              val bodyBytes = response
              Triple(status in 200..399, status, bodyBytes.size)
            }
          }

      result.fold(
          onSuccess = { (success, status, bodySize) ->
            if (success) {
              TSLog.d(
                  AdbTcpHttpTestContract.TAG_TEST,
                  "event=TEST_PASS requestId=$requestId scenario=$scenario route=DATAPATH protocol=http bytesSent=0 bytesReceived=$bodySize detail=http_status_$status")
            } else {
              TSLog.e(
                  AdbTcpHttpTestContract.TAG_TEST,
                  "event=TEST_FAIL requestId=$requestId scenario=$scenario route=DATAPATH reason=http_status_$status")
            }
          },
          onFailure = { error ->
            TSLog.e(
                AdbTcpHttpTestContract.TAG_TEST,
                "event=TEST_FAIL requestId=$requestId scenario=$scenario route=DATAPATH reason=${sanitize(error.message ?: error.javaClass.simpleName)}")
          })
      finish()
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    scope.cancel()
  }

  private fun sanitize(value: String): String {
    return value.replace(Regex("\\s+"), "_").replace(Regex("[^a-zA-Z0-9_./:=-]"), "-")
  }
}
