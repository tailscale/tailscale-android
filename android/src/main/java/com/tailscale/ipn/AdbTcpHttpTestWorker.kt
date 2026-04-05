// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.tailscale.ipn.util.TSLog
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale

class AdbTcpHttpTestWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

  override suspend fun doWork(): Result {
    val request = TestRequest.from(inputData)

    TSLog.d(
        AdbTcpHttpTestContract.TAG_TEST,
        "event=request_start requestId=${request.requestId} scenario=${request.scenario} protocol=${request.protocol} host=${request.host} port=${request.port} timeoutMs=${request.timeoutMs} socksEnabled=${request.socksEnabled}")

    val validationError = validate(request)
    if (validationError != null) {
      return fail(request, validationError)
    }

    val routeDecision = decideRoute(request.host, request.port, request.socksEnabled)
    TSLog.d(
        AdbTcpHttpTestContract.TAG_ROUTE,
        "event=route_decision requestId=${request.requestId} host=${request.host} port=${request.port} matchedRule=${routeDecision.matchedRule} selectedRoute=${routeDecision.route.name}")

    return runCatching { execute(request, routeDecision) }
        .fold(
            onSuccess = { result ->
              TSLog.d(
                  AdbTcpHttpTestContract.TAG_TEST,
                  "event=TEST_PASS requestId=${request.requestId} scenario=${request.scenario} route=${routeDecision.route.name} protocol=${request.protocol} bytesSent=${result.bytesSent} bytesReceived=${result.bytesReceived} detail=${sanitizeForLog(result.detail)}")
              Result.success(
                  Data.Builder()
                      .putString("route", routeDecision.route.name)
                      .putString("matchedRule", routeDecision.matchedRule)
                      .putInt("bytesSent", result.bytesSent)
                      .putInt("bytesReceived", result.bytesReceived)
                      .build())
            },
            onFailure = { error ->
              fail(request, error.message ?: error.javaClass.simpleName, routeDecision)
            })
  }

  private fun execute(request: TestRequest, routeDecision: RouteDecision): ValidationResult {
    val socket = openSocket(request, routeDecision)
    socket.use { connectedSocket ->
      connectedSocket.soTimeout = request.timeoutMsInt
      return when (request.protocol) {
        "http" -> runHttp(request, routeDecision, connectedSocket)
        "tcp" -> runTcp(request, routeDecision, connectedSocket)
        else -> throw IllegalArgumentException("unsupported_protocol")
      }
    }
  }

  private fun openSocket(request: TestRequest, routeDecision: RouteDecision): Socket {
    return when (routeDecision.route) {
      Route.DIRECT, Route.TAILSCALE_NORMAL -> connectDirect(request, routeDecision.route)
      Route.TAILNET_SOCKS -> connectViaSocks(request)
    }
  }

  private fun connectDirect(request: TestRequest, route: Route): Socket {
    val socket = Socket()
    return try {
      socket.connect(InetSocketAddress(request.host, request.port), request.timeoutMsInt)
      TSLog.d(
          AdbTcpHttpTestContract.TAG_TEST,
          "event=target_connect_success requestId=${request.requestId} route=${route.name} host=${request.host} port=${request.port}")
      socket
    } catch (e: Exception) {
      TSLog.e(
          AdbTcpHttpTestContract.TAG_TEST,
          "event=target_connect_fail requestId=${request.requestId} route=${route.name} host=${request.host} port=${request.port} reason=${sanitizeForLog(e.message ?: e.javaClass.simpleName)}")
      socket.closeQuietly()
      throw e
    }
  }

  private fun connectViaSocks(request: TestRequest): Socket {
    val socket = Socket()
    try {
      socket.connect(
          InetSocketAddress(
              AdbTcpHttpTestContract.SOCKS_SERVER_HOST, AdbTcpHttpTestContract.SOCKS_SERVER_PORT),
          request.timeoutMsInt)
      socket.soTimeout = request.timeoutMsInt
      TSLog.d(
          AdbTcpHttpTestContract.TAG_SOCKS,
          "event=socks_server_connect_success requestId=${request.requestId} socksHost=${AdbTcpHttpTestContract.SOCKS_SERVER_HOST} socksPort=${AdbTcpHttpTestContract.SOCKS_SERVER_PORT}")

      val output = socket.getOutputStream()
      val input = socket.getInputStream()
      writeAll(output, byteArrayOf(0x05.toByte(), 0x01.toByte(), 0x00.toByte()))
      val methodResponse = readExact(input, 2)
      if (methodResponse[0] != 0x05.toByte() || methodResponse[1] != 0x00.toByte()) {
        throw IllegalStateException(
            "socks_method_rejected_${methodResponse[0].toUByte().toString()}_${methodResponse[1].toUByte().toString()}")
      }

      val connectRequest = buildSocksConnectRequest(request.host, request.port)
      writeAll(output, connectRequest)
      val responseHeader = readExact(input, 4)
      if (responseHeader[0] != 0x05.toByte()) {
        throw IllegalStateException("socks_bad_version_${responseHeader[0].toUByte().toString()}")
      }
      if (responseHeader[1] != 0x00.toByte()) {
        throw IllegalStateException("socks_connect_reply_${responseHeader[1].toUByte().toString()}")
      }

      discardSocksAddress(input, responseHeader[3].toInt() and 0xff)
      TSLog.d(
          AdbTcpHttpTestContract.TAG_SOCKS,
          "event=socks_connect_success requestId=${request.requestId} targetHost=${request.host} targetPort=${request.port}")
      TSLog.d(
          AdbTcpHttpTestContract.TAG_TEST,
          "event=target_connect_success requestId=${request.requestId} route=${Route.TAILNET_SOCKS.name} host=${request.host} port=${request.port}")
      return socket
    } catch (e: Exception) {
      TSLog.e(
          AdbTcpHttpTestContract.TAG_SOCKS,
          "event=socks_connect_fail requestId=${request.requestId} targetHost=${request.host} targetPort=${request.port} reason=${sanitizeForLog(e.message ?: e.javaClass.simpleName)}")
      TSLog.e(
          AdbTcpHttpTestContract.TAG_TEST,
          "event=target_connect_fail requestId=${request.requestId} route=${Route.TAILNET_SOCKS.name} host=${request.host} port=${request.port} reason=${sanitizeForLog(e.message ?: e.javaClass.simpleName)}")
      socket.closeQuietly()
      throw e
    }
  }

  private fun runHttp(
      request: TestRequest,
      routeDecision: RouteDecision,
      socket: Socket,
  ): ValidationResult {
    val method = if (request.payload.isEmpty()) "GET" else "POST"
    val path = normalizeHttpPath(request.path)
    val bodyBytes = request.payload.toByteArray(StandardCharsets.UTF_8)
    val requestBytes =
        buildString {
              append(method)
              append(' ')
              append(path)
              append(" HTTP/1.1\r\n")
              append("Host: ${request.host}\r\n")
              append("Connection: close\r\n")
              append("User-Agent: tailscale-android-tsocks-test\r\n")
              if (bodyBytes.isNotEmpty()) {
                append("Content-Type: text/plain; charset=utf-8\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
              }
              append("\r\n")
            }
            .toByteArray(StandardCharsets.UTF_8)

    writeAll(socket.getOutputStream(), requestBytes)
    var bytesSent = requestBytes.size
    if (bodyBytes.isNotEmpty()) {
      writeAll(socket.getOutputStream(), bodyBytes)
      bytesSent += bodyBytes.size
    }

    val responseBytes = readAvailable(socket.getInputStream(), request.timeoutMsInt)
    if (responseBytes.isEmpty()) {
      throw IllegalStateException("http_empty_response")
    }
    val responseText = responseBytes.toString(StandardCharsets.UTF_8)
    val statusLine = responseText.lineSequence().firstOrNull()?.trim().orEmpty()
    if (!statusLine.startsWith("HTTP/1.")) {
      throw IllegalStateException("http_bad_status_line")
    }
    val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
    if (statusCode !in 200..399) {
      throw IllegalStateException("http_status_$statusCode")
    }
    TSLog.d(
        AdbTcpHttpTestContract.TAG_TEST,
        "event=http_result requestId=${request.requestId} route=${routeDecision.route.name} statusLine=${sanitizeForLog(statusLine)} bytesSent=${bytesSent} bytesReceived=${responseBytes.size}")
    return ValidationResult(bytesSent, responseBytes.size, statusLine)
  }

  private fun runTcp(
      request: TestRequest,
      routeDecision: RouteDecision,
      socket: Socket,
  ): ValidationResult {
    val rawPayload =
        if (request.payload.isNotEmpty()) {
          request.payload
        } else {
          "tailscale-tsocks-test requestId=${request.requestId} scenario=${request.scenario}\n"
        }
    val expectsPong = rawPayload.trim().equals("PING", ignoreCase = true)
    val payload = if (expectsPong) "PING\n" else rawPayload
    val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
    writeAll(socket.getOutputStream(), payloadBytes)
    val responseBytes =
        if (expectsPong) {
          readUntil(socket.getInputStream(), "PONG", request.timeoutMsInt)
        } else {
          readAvailable(socket.getInputStream(), request.timeoutMsInt)
        }
    if (responseBytes.isEmpty()) {
      throw IllegalStateException("tcp_empty_response")
    }
    val responseText = responseBytes.toString(StandardCharsets.UTF_8).trim()
    if (expectsPong && !responseText.contains("PONG")) {
      throw IllegalStateException("tcp_missing_pong")
    }
    TSLog.d(
        AdbTcpHttpTestContract.TAG_TEST,
        "event=tcp_result requestId=${request.requestId} route=${routeDecision.route.name} bytesSent=${payloadBytes.size} bytesReceived=${responseBytes.size} response=${sanitizeForLog(responseText)}")
    return ValidationResult(payloadBytes.size, responseBytes.size, "tcp_response_received")
  }

  @VisibleForTesting
  internal fun decideRoute(host: String, port: Int, socksEnabled: Boolean): RouteDecision {
    val normalizedHost = host.trim().lowercase(Locale.US)
    return when {
      normalizedHost == AdbTcpHttpTestContract.LAN_HOST.lowercase(Locale.US) ->
          RouteDecision(Route.DIRECT, "lan_baseline")
      normalizedHost == AdbTcpHttpTestContract.TAILNET_LAB_HOST.lowercase(Locale.US) ->
          RouteDecision(Route.TAILSCALE_NORMAL, "tailnet_lab_baseline")
      normalizedHost == AdbTcpHttpTestContract.TAILNET_DOMAIN_HOST.lowercase(Locale.US) ->
          RouteDecision(Route.TAILSCALE_NORMAL, "tailnet_domain_baseline")
      normalizedHost == AdbTcpHttpTestContract.SOCKS_SERVER_HOST.lowercase(Locale.US) &&
          port == AdbTcpHttpTestContract.SOCKS_SERVER_PORT ->
          RouteDecision(Route.DIRECT, "socks_server_self")
      !socksEnabled -> RouteDecision(Route.DIRECT, "socks_disabled")
      normalizedHost == AdbTcpHttpTestContract.PUBLIC_ALLOWLIST_HOST.lowercase(Locale.US) &&
          port == AdbTcpHttpTestContract.PUBLIC_ALLOWLIST_PORT ->
          RouteDecision(Route.TAILNET_SOCKS, "public_allowlist_example_com_80")
      else -> RouteDecision(Route.DIRECT, "default_direct")
    }
  }

  private fun validate(request: TestRequest): String? {
    if (request.host.isBlank()) {
      return "missing_host"
    }
    if (request.port !in 1..65535) {
      return "invalid_port"
    }
    if (request.protocol != "tcp" && request.protocol != "http") {
      return "invalid_protocol"
    }
    if (request.timeoutMs <= 0L) {
      return "invalid_timeout"
    }
    if (request.timeoutMs > 10_000L) {
      return "timeout_too_large"
    }
    return null
  }

  private fun fail(
      request: TestRequest,
      reason: String,
      routeDecision: RouteDecision? = null,
  ): Result {
    TSLog.e(
        AdbTcpHttpTestContract.TAG_TEST,
        "event=TEST_FAIL requestId=${request.requestId} scenario=${request.scenario} route=${routeDecision?.route?.name ?: "UNKNOWN"} reason=${sanitizeForLog(reason)}")
    return Result.failure(
        Data.Builder()
            .putString("reason", reason)
            .putString("route", routeDecision?.route?.name ?: "UNKNOWN")
            .putString("matchedRule", routeDecision?.matchedRule ?: "unknown")
            .build())
  }

  private fun buildSocksConnectRequest(host: String, port: Int): ByteArray {
    val hostBytes = host.toByteArray(StandardCharsets.UTF_8)
    val ipv4Bytes = parseIpv4(host)
    val request = ByteArrayOutputStream()
    request.write(byteArrayOf(0x05.toByte(), 0x01.toByte(), 0x00.toByte()))
    if (ipv4Bytes != null) {
      request.write(0x01)
      request.write(ipv4Bytes)
    } else {
      require(hostBytes.size <= 255) { "host_too_long" }
      request.write(0x03)
      request.write(hostBytes.size)
      request.write(hostBytes)
    }
    request.write(byteArrayOf(((port ushr 8) and 0xff).toByte(), (port and 0xff).toByte()))
    return request.toByteArray()
  }

  private fun parseIpv4(host: String): ByteArray? {
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
    return if (address is Inet4Address && address.hostAddress == host) {
      address.address
    } else {
      null
    }
  }

  private fun discardSocksAddress(input: InputStream, atyp: Int) {
    val addressLength =
        when (atyp) {
          0x01 -> 4
          0x03 -> readExact(input, 1)[0].toInt() and 0xff
          0x04 -> 16
          else -> throw IllegalStateException("socks_unknown_atyp_$atyp")
        }
    readExact(input, addressLength)
    readExact(input, 2)
  }

  private fun readExact(input: InputStream, length: Int): ByteArray {
    val buffer = ByteArray(length)
    var offset = 0
    while (offset < length) {
      val bytesRead = input.read(buffer, offset, length - offset)
      if (bytesRead < 0) {
        throw EOFException("unexpected_eof")
      }
      offset += bytesRead
    }
    return buffer
  }

  private fun readAvailable(input: InputStream, timeoutMs: Int): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(1024)
    val maxBytes = 8 * 1024
    while (buffer.size() < maxBytes) {
      val bytesRead = input.read(chunk)
      if (bytesRead < 0) {
        break
      }
      if (bytesRead == 0) {
        break
      }
      buffer.write(chunk, 0, bytesRead)
      if (input.available() <= 0 || buffer.size() >= maxBytes) {
        break
      }
    }
    if (buffer.size() == 0) {
      throw java.net.SocketTimeoutException("read_timeout_${timeoutMs}")
    }
    return buffer.toByteArray()
  }

  private fun readUntil(input: InputStream, marker: String, timeoutMs: Int): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(1024)
    val maxBytes = 8 * 1024
    while (buffer.size() < maxBytes) {
      val bytesRead = input.read(chunk)
      if (bytesRead < 0) {
        break
      }
      if (bytesRead == 0) {
        break
      }
      buffer.write(chunk, 0, bytesRead)
      val text = buffer.toByteArray().toString(StandardCharsets.UTF_8)
      if (text.contains(marker)) {
        break
      }
    }
    if (buffer.size() == 0) {
      throw java.net.SocketTimeoutException("read_timeout_${timeoutMs}")
    }
    return buffer.toByteArray()
  }

  private fun writeAll(output: OutputStream, bytes: ByteArray) {
    output.write(bytes)
    output.flush()
  }

  private fun normalizeHttpPath(path: String): String {
    if (path.isBlank()) {
      return AdbTcpHttpTestContract.DEFAULT_PATH
    }
    return if (path.startsWith('/')) path else "/$path"
  }

  private fun sanitizeForLog(value: String): String {
    return value.replace(Regex("\\s+"), "_").replace(Regex("[^a-zA-Z0-9_./:=-]"), "-")
  }

  private fun Socket.closeQuietly() {
    runCatching { close() }
  }

  private data class ValidationResult(val bytesSent: Int, val bytesReceived: Int, val detail: String)

  internal data class RouteDecision(val route: Route, val matchedRule: String)

  internal enum class Route {
    DIRECT,
    TAILSCALE_NORMAL,
    TAILNET_SOCKS,
  }

  private data class TestRequest(
      val scenario: String,
      val requestId: String,
      val host: String,
      val port: Int,
      val protocol: String,
      val path: String,
      val payload: String,
      val timeoutMs: Long,
      val socksEnabled: Boolean,
  ) {
    val timeoutMsInt: Int
      get() = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    companion object {
      fun from(data: Data): TestRequest {
        val protocol =
            data.getString(AdbTcpHttpTestContract.EXTRA_PROTOCOL)
                ?.trim()
                ?.lowercase(Locale.US)
                .orEmpty()
                .ifEmpty { AdbTcpHttpTestContract.DEFAULT_PROTOCOL }
        return TestRequest(
            scenario =
                data.getString(AdbTcpHttpTestContract.EXTRA_SCENARIO)
                    ?.trim()
                    .orEmpty()
                    .ifEmpty { "unspecified" },
            requestId =
                data.getString(AdbTcpHttpTestContract.EXTRA_REQUEST_ID)
                    ?.trim()
                    .orEmpty()
                    .ifEmpty { "req-${System.currentTimeMillis()}" },
            host = data.getString(AdbTcpHttpTestContract.EXTRA_HOST)?.trim().orEmpty(),
            port = data.getInt(AdbTcpHttpTestContract.EXTRA_PORT, -1),
            protocol = protocol,
            path = data.getString(AdbTcpHttpTestContract.EXTRA_PATH)?.trim().orEmpty(),
            payload = data.getString(AdbTcpHttpTestContract.EXTRA_PAYLOAD).orEmpty(),
            timeoutMs =
                data.getLong(
                    AdbTcpHttpTestContract.EXTRA_TIMEOUT_MS,
                    AdbTcpHttpTestContract.DEFAULT_TIMEOUT_MS),
            socksEnabled =
                data.getBoolean(
                    AdbTcpHttpTestContract.EXTRA_SOCKS_ENABLED,
                    AdbTcpHttpTestContract.DEFAULT_SOCKS_ENABLED),
        )
      }
    }
  }
}
