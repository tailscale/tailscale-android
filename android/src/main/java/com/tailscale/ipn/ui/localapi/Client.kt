// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.localapi

import android.content.Context
import android.util.Log
import com.tailscale.ipn.ui.model.BugReportID
import com.tailscale.ipn.ui.model.Errors
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.IpnState
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.util.InputStreamAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.serializer
import libtailscale.FilePart
import java.nio.charset.Charset
import kotlin.reflect.KType
import kotlin.reflect.typeOf

private object Endpoint {
  const val DEBUG = "debug"
  const val DEBUG_LOG = "debug-log"
  const val BUG_REPORT = "bugreport"
  const val PREFS = "prefs"
  const val FILE_TARGETS = "file-targets"
  const val UPLOAD_METRICS = "upload-client-metrics"
  const val START = "start"
  const val LOGIN_INTERACTIVE = "login-interactive"
  const val RESET_AUTH = "reset-auth"
  const val LOGOUT = "logout"
  const val PROFILES = "profiles/"
  const val PROFILES_CURRENT = "profiles/current"
  const val STATUS = "status"
  const val TKA_STATUS = "tka/status"
  const val TKA_SIGN = "tka/sign"
  const val TKA_VERIFY_DEEP_LINK = "tka/verify-deeplink"
  const val PING = "ping"
  const val FILES = "files"
  const val FILE_PUT = "file-put"
  const val TAILFS_SERVER_ADDRESS = "tailfs/fileserver-address"
  const val ENABLE_EXIT_NODE = "set-use-exit-node-enabled"
}

typealias StatusResponseHandler = (Result<IpnState.Status>) -> Unit

typealias TailnetLockStatusResponseHandler = (Result<IpnState.NetworkLockStatus>) -> Unit

typealias BugReportIdHandler = (Result<BugReportID>) -> Unit

typealias PrefsHandler = (Result<Ipn.Prefs>) -> Unit

/**
 * Client provides a mechanism for calling Go's LocalAPIClient. Every LocalAPI endpoint has a
 * corresponding method on this Client.
 */
class Client(private val scope: CoroutineScope) {
  private val TAG = Client::class.simpleName

  fun start(options: Ipn.Options, responseHandler: (Result<Unit>) -> Unit) {
    val body = Json.encodeToString(options).toByteArray()
    return post(Endpoint.START, body, responseHandler = responseHandler)
  }

  fun status(responseHandler: StatusResponseHandler) {
    get(Endpoint.STATUS, responseHandler = responseHandler)
  }

  fun bugReportId(responseHandler: BugReportIdHandler) {
    post(Endpoint.BUG_REPORT, responseHandler = responseHandler)
  }

  fun prefs(responseHandler: PrefsHandler) {
    get(Endpoint.PREFS, responseHandler = responseHandler)
  }

  fun editPrefs(prefs: Ipn.MaskedPrefs, responseHandler: (Result<Ipn.Prefs>) -> Unit) {
    val body = Json.encodeToString(prefs).toByteArray()
    return patch(Endpoint.PREFS, body, responseHandler = responseHandler)
  }

  fun setUseExitNode(use: Boolean, responseHandler: (Result<Ipn.Prefs>) -> Unit) {
    val path = "${Endpoint.ENABLE_EXIT_NODE}?enabled=$use"
    return post(path, responseHandler = responseHandler)
  }

  fun profiles(responseHandler: (Result<List<IpnLocal.LoginProfile>>) -> Unit) {
    get(Endpoint.PROFILES, responseHandler = responseHandler)
  }

  fun currentProfile(responseHandler: (Result<IpnLocal.LoginProfile>) -> Unit) {
    return get(Endpoint.PROFILES_CURRENT, responseHandler = responseHandler)
  }

  fun addProfile(responseHandler: (Result<String>) -> Unit = {}) {
    return put(Endpoint.PROFILES, responseHandler = responseHandler)
  }

  fun deleteProfile(
      profile: IpnLocal.LoginProfile,
      responseHandler: (Result<String>) -> Unit = {}
  ) {
    return delete(Endpoint.PROFILES + profile.ID, responseHandler = responseHandler)
  }

  fun switchProfile(
      profile: IpnLocal.LoginProfile,
      responseHandler: (Result<String>) -> Unit = {}
  ) {
    return post(Endpoint.PROFILES + profile.ID, responseHandler = responseHandler)
  }

  fun startLoginInteractive(responseHandler: (Result<Unit>) -> Unit) {
    return post(Endpoint.LOGIN_INTERACTIVE, responseHandler = responseHandler)
  }

  fun logout(responseHandler: (Result<String>) -> Unit) {
    return post(Endpoint.LOGOUT, responseHandler = responseHandler)
  }

  fun tailnetLockStatus(responseHandler: TailnetLockStatusResponseHandler) {
    get(Endpoint.TKA_STATUS, responseHandler = responseHandler)
  }

  fun fileTargets(responseHandler: (Result<List<Ipn.FileTarget>>) -> Unit) {
    get(Endpoint.FILE_TARGETS, responseHandler = responseHandler)
  }

  fun putTaildropFiles(
      context: Context,
      peerId: StableNodeID,
      files: Collection<Ipn.OutgoingFile>,
      responseHandler: (Result<String>) -> Unit
  ) {
    val manifest = Json.encodeToString(files)
    val manifestPart = FilePart()
    manifestPart.body = InputStreamAdapter(manifest.byteInputStream(Charset.defaultCharset()))
    manifestPart.filename = "manifest.json"
    manifestPart.contentType = "application/json"
    val parts = mutableListOf(manifestPart)

    try {
      parts.addAll(
          files.map { file ->
            val stream =
                context.contentResolver.openInputStream(file.uri)
                    ?: throw Exception("Error opening file stream")

            val part = FilePart()
            part.filename = file.Name
            part.contentLength = file.DeclaredSize
            part.body = InputStreamAdapter(stream)
            part
          })
    } catch (e: Exception) {
      parts.forEach { it.body.close() }
      Log.e(TAG, "Error creating file upload body: $e")
      responseHandler(Result.failure(e))
      return
    }

    return postMultipart(
        "${Endpoint.FILE_PUT}/${peerId}",
        FileParts(parts),
        responseHandler,
    )
  }

  private inline fun <reified T> get(
      path: String,
      body: ByteArray? = null,
      noinline responseHandler: (Result<T>) -> Unit
  ) {
    Request(
            scope = scope,
            method = "GET",
            path = path,
            body = body,
            responseType = typeOf<T>(),
            responseHandler = responseHandler)
        .execute()
  }

  private inline fun <reified T> put(
      path: String,
      body: ByteArray? = null,
      noinline responseHandler: (Result<T>) -> Unit
  ) {
    Request(
            scope = scope,
            method = "PUT",
            path = path,
            body = body,
            responseType = typeOf<T>(),
            responseHandler = responseHandler)
        .execute()
  }

  private inline fun <reified T> post(
      path: String,
      body: ByteArray? = null,
      noinline responseHandler: (Result<T>) -> Unit
  ) {
    Request(
            scope = scope,
            method = "POST",
            path = path,
            body = body,
            responseType = typeOf<T>(),
            responseHandler = responseHandler)
        .execute()
  }

  private inline fun <reified T> postMultipart(
      path: String,
      parts: FileParts,
      noinline responseHandler: (Result<T>) -> Unit
  ) {
    Request(
            scope = scope,
            method = "POST",
            path = path,
            parts = parts,
            timeoutMillis = 24 * 60 * 60 * 1000, // 24 hours
            responseType = typeOf<T>(),
            responseHandler = responseHandler)
        .execute()
  }

  private inline fun <reified T> patch(
      path: String,
      body: ByteArray? = null,
      noinline responseHandler: (Result<T>) -> Unit
  ) {
    Request(
            scope = scope,
            method = "PATCH",
            path = path,
            body = body,
            responseType = typeOf<T>(),
            responseHandler = responseHandler)
        .execute()
  }

  private inline fun <reified T> delete(
      path: String,
      noinline responseHandler: (Result<T>) -> Unit
  ) {
    Request(
            scope = scope,
            method = "DELETE",
            path = path,
            responseType = typeOf<T>(),
            responseHandler = responseHandler)
        .execute()
  }
}

class Request<T>(
    private val scope: CoroutineScope,
    private val method: String,
    path: String,
    private val body: ByteArray? = null,
    private val parts: FileParts? = null,
    private val timeoutMillis: Long = 30000,
    private val responseType: KType,
    private val responseHandler: (Result<T>) -> Unit
) {
  private val fullPath = "/localapi/v0/$path"

  companion object {
    private const val TAG = "LocalAPIRequest"

    private val jsonDecoder = Json { ignoreUnknownKeys = true }

    private lateinit var app: libtailscale.Application

    @JvmStatic
    fun setApp(newApp: libtailscale.Application) {
      app = newApp
    }
  }

  @OptIn(ExperimentalSerializationApi::class)
  fun execute() {
    scope.launch(Dispatchers.IO) {
      Log.d(TAG, "Executing request:${method}:${fullPath} on app $app")
      try {
        val resp =
            if (parts != null) app.callLocalAPIMultipart(timeoutMillis, method, fullPath, parts)
            else
                app.callLocalAPI(
                    timeoutMillis,
                    method,
                    fullPath,
                    body?.let { InputStreamAdapter(it.inputStream()) })
        // TODO: use the streaming body for performance
        // An empty body is a perfectly valid response and indicates success
        val respData = resp.bodyBytes() ?: ByteArray(0)
        val response: Result<T> =
            when (responseType) {
              typeOf<String>() -> Result.success(respData.decodeToString() as T)
              typeOf<Unit>() -> Result.success(Unit as T)
              else ->
                  try {
                    Result.success(
                        jsonDecoder.decodeFromStream(
                            Json.serializersModule.serializer(responseType), respData.inputStream())
                            as T)
                  } catch (t: Throwable) {
                    // If we couldn't parse the response body, assume it's an error response
                    try {
                      val error =
                          jsonDecoder.decodeFromStream<Errors.GenericError>(respData.inputStream())
                      throw Exception(error.error)
                    } catch (t: Throwable) {
                      Result.failure(t)
                    }
                  }
            }
        if (resp.statusCode() >= 400) {
          throw Exception(
              "Request failed with status ${resp.statusCode()}: ${respData.toString(Charset.defaultCharset())}")
        }
        // The response handler will invoked internally by the request parser
        scope.launch { responseHandler(response) }
      } catch (e: Exception) {
        Log.e(TAG, "Error executing request:${method}:${fullPath}: $e")
        scope.launch { responseHandler(Result.failure(e)) }
      }
    }
  }
}

class FileParts(private val parts: List<FilePart>) : libtailscale.FileParts {
  override fun get(i: Int): FilePart {
    return parts[i]
  }

  override fun len(): Int {
    return parts.size
  }
}
