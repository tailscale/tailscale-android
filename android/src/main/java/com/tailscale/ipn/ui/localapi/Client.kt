// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.localapi

import android.util.Log
import com.tailscale.ipn.ui.model.BugReportID
import com.tailscale.ipn.ui.model.Errors
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.serializer
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
}

typealias BugReportIdHandler = (Result<BugReportID>) -> Unit
typealias PrefsHandler = (Result<Ipn.Prefs>) -> Unit

/**
 * Client provides a mechanism for calling Go's LocalAPIClient. Every LocalAPI endpoint has a
 * corresponding method on this Client.
 */
class Client(private val scope: CoroutineScope) {
    fun bugReportId(responseHandler: BugReportIdHandler) {
        post(Endpoint.BUG_REPORT, responseHandler = responseHandler)
    }

    fun prefs(responseHandler: PrefsHandler) {
        get(Endpoint.PREFS, responseHandler = responseHandler)
    }

    fun editPrefs(
        prefs: Ipn.MaskedPrefs, responseHandler: (Result<Ipn.Prefs>) -> Unit
    ) {
        val body = Json.encodeToString(prefs).toByteArray()
        return patch(Endpoint.PREFS, body, responseHandler = responseHandler)
    }

    fun profiles(responseHandler: (Result<List<IpnLocal.LoginProfile>>) -> Unit) {
        get(Endpoint.PROFILES, responseHandler = responseHandler)
    }

    fun currentProfile(responseHandler: (Result<IpnLocal.LoginProfile>) -> Unit) {
        return get(Endpoint.PROFILES_CURRENT, responseHandler = responseHandler)
    }

    fun startLoginInteractive(responseHandler: (Result<String>) -> Unit) {
        return post(Endpoint.LOGIN_INTERACTIVE, responseHandler = responseHandler)
    }

    fun logout(responseHandler: (Result<String>) -> Unit) {
        return post(Endpoint.LOGOUT, responseHandler = responseHandler)
    }

    private inline fun <reified T> get(
        path: String, body: ByteArray? = null, noinline responseHandler: (Result<T>) -> Unit
    ) {
        Request(
            scope = scope,
            method = "GET",
            path = path,
            body = body,
            responseType = typeOf<T>(),
            responseHandler = responseHandler
        ).execute()
    }

    private inline fun <reified T> put(
        path: String, body: ByteArray? = null, noinline responseHandler: (Result<T>) -> Unit
    ) {
        Request(
            scope = scope,
            method = "PUT",
            path = path,
            body = body,
            responseType = typeOf<T>(),
            responseHandler = responseHandler
        ).execute()
    }

    private inline fun <reified T> post(
        path: String, body: ByteArray? = null, noinline responseHandler: (Result<T>) -> Unit
    ) {
        Request(
            scope = scope,
            method = "POST",
            path = path,
            body = body,
            responseType = typeOf<T>(),
            responseHandler = responseHandler
        ).execute()
    }

    private inline fun <reified T> patch(
        path: String, body: ByteArray? = null, noinline responseHandler: (Result<T>) -> Unit
    ) {
        Request(
            scope = scope,
            method = "PATCH",
            path = path,
            body = body,
            responseType = typeOf<T>(),
            responseHandler = responseHandler
        ).execute()
    }

    private inline fun <reified T> delete(
        path: String, noinline responseHandler: (Result<T>) -> Unit
    ) {
        Request(
            scope = scope,
            method = "DELETE",
            path = path,
            responseType = typeOf<T>(),
            responseHandler = responseHandler
        ).execute()
    }
}

class Request<T>(
    private val scope: CoroutineScope,
    private val method: String,
    path: String,
    private val body: ByteArray? = null,
    private val responseType: KType,
    private val responseHandler: (Result<T>) -> Unit
) {
    private val fullPath = "/localapi/v0/$path"

    companion object {
        private const val TAG = "LocalAPIRequest"

        private val jsonDecoder = Json { ignoreUnknownKeys = true }
        private val isReady = CompletableDeferred<Boolean>()

        // Called by the backend when the localAPI is ready to accept requests.
        @JvmStatic
        @Suppress("unused")
        fun onReady() {
            isReady.complete(true)
            Log.d(TAG, "Ready")
        }
    }

    // Perform a request to the local API in the go backend.  This is
    // the primary JNI method for servicing a localAPI call. This
    // is GUARANTEED to call back into onResponse.
    // @see cmd/localapiclient/localapishim.go
    //
    // method: The HTTP method to use.
    // request: The path to the localAPI endpoint.
    // body: The body of the request.
    private external fun doRequest(method: String, request: String, body: ByteArray?)

    fun execute() {
        scope.launch(Dispatchers.IO) {
            isReady.await()
            Log.d(TAG, "Executing request:${method}:${fullPath}")
            doRequest(method, fullPath, body)
        }
    }

    // This is called from the JNI layer to publish responses.
    @OptIn(ExperimentalSerializationApi::class)
    @Suppress("unused", "UNCHECKED_CAST")
    fun onResponse(respData: ByteArray) {
        Log.d(TAG, "Response for request: $fullPath")

        val response: Result<T> = when (responseType) {
            typeOf<String>() -> Result.success(respData.decodeToString() as T)
            else -> try {
                Result.success(
                    jsonDecoder.decodeFromStream(
                        Json.serializersModule.serializer(responseType), respData.inputStream()
                    ) as T
                )
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

        // The response handler will invoked internally by the request parser
        scope.launch {
            responseHandler(response)
        }
    }
}
