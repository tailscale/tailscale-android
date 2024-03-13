// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.localapi

import android.util.Log
import com.tailscale.ipn.ui.model.BugReportID
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.IpnState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

typealias StatusResponseHandler = (Result<IpnState.Status>) -> Unit
typealias BugReportIdHandler = (Result<BugReportID>) -> Unit
typealias PrefsHandler = (Result<Ipn.Prefs>) -> Unit

class LocalApiClient(private val scope: CoroutineScope) {
    init {
        Log.d("LocalApiClient", "LocalApiClient created")
    }

    companion object {
        val isReady = CompletableDeferred<Boolean>()

        // Called by the backend when the localAPI is ready to accept requests.
        @JvmStatic
        @Suppress("unused")
        fun onReady() {
            isReady.complete(true)
            Log.d("LocalApiClient", "LocalApiClient is ready")
        }
    }

    // Perform a request to the local API in the go backend.  This is
    // the primary JNI method for servicing a localAPI call. This
    // is GUARANTEED to call back into onResponse with the response
    // from the backend with a matching cookie.
    // @see cmd/localapiclient/localapishim.go
    //
    // request: The path to the localAPI endpoint.
    // method: The HTTP method to use.
    // body: The body of the request.
    // cookie: A unique identifier for this request.  This is used map responses to
    //         the corresponding request.  Cookies must be unique for each request.
    private external fun doRequest(request: String, method: String, body: ByteArray?, cookie: String)

    fun <T> executeRequest(request: LocalAPIRequest<T>) {
        scope.launch {
            isReady.await()
            Log.d("LocalApiClient", "Executing request:${request.method}:${request.path}")
            requests[request.cookie] = request
            doRequest(request.path, request.method, request.body, request.cookie)
        }
    }

    // This is called from the JNI layer to publish localAPIResponses.  This should execute on the
    // same thread that called doRequest.
    @Suppress("unused")
    fun onResponse(response: ByteArray, cookie: String) {
        requests.remove(cookie)?.let { request ->
            Log.d("LocalApiClient", "Response for request:${request.path} cookie:${request.cookie}")
            // The response handler will invoked internally by the request parser
            request.parser(response)
        } ?: { Log.e("LocalApiClient", "Received response for unknown request: $cookie") }
    }

    // Tracks in-flight requests and their callback handlers by cookie. This should
    // always be manipulated via the addRequest and removeRequest methods.
    private var requests = ConcurrentHashMap<String, LocalAPIRequest<*>>()

    // localapi Invocations

    fun getStatus(responseHandler: StatusResponseHandler) {
        val req = LocalAPIRequest.status(responseHandler)
        executeRequest(req)
    }

    fun getBugReportId(responseHandler: BugReportIdHandler) {
        val req = LocalAPIRequest.bugReportId(responseHandler)
        executeRequest(req)
    }

    fun getPrefs(responseHandler: PrefsHandler) {
        val req = LocalAPIRequest.prefs(responseHandler)
        executeRequest(req)
    }

    fun editPrefs(prefs: Ipn.MaskedPrefs, responseHandler: (Result<Ipn.Prefs>) -> Unit) {
        val req = LocalAPIRequest.editPrefs(prefs, responseHandler)
        executeRequest<Ipn.Prefs>(req)
    }

    fun getProfiles(responseHandler: (Result<List<IpnLocal.LoginProfile>>) -> Unit) {
        val req = LocalAPIRequest.profiles(responseHandler)
        executeRequest(req)
    }

    fun getCurrentProfile(responseHandler: (Result<IpnLocal.LoginProfile>) -> Unit) {
        val req = LocalAPIRequest.currentProfile(responseHandler)
        executeRequest(req)
    }

    fun startLoginInteractive() {
        val req = LocalAPIRequest.startLoginInteractive { result ->
            result.success?.let { Log.d("LocalApiClient", "Login started: $it") }
                    ?: run { Log.e("LocalApiClient", "Error starting login: ${result.error}") }
        }
        executeRequest<String>(req)
    }

    fun logout() {
        val req = LocalAPIRequest.logout { result ->
            result.success?.let { Log.d("LocalApiClient", "Logout started: $it") }
                    ?: run { Log.e("LocalApiClient", "Error starting logout: ${result.error}") }
        }
        executeRequest<String>(req)
    }

    // (jonathan) TODO: A (likely) exhaustive list of localapi endpoints required for
    // a fully functioning client.  This is a work in progress and will be updated
    // See: corp/xcode/Shared/LocalAPIClient.swift for the various verbs, parameters,
    // and body contents for each endpoint.  Endpoints are defined in LocalAPIEndpoint
    //
    // fetchFileTargets
    // sendFiles
    // getWaitingFiles
    // recieveWaitingFile
    // inidicateFileRecieved
    // debug
    // debugLog
    // uploadClientMetrics
    // start
    // startLoginInteractive
    // logout
    // addProfile
    // switchProfile
    // deleteProfile
    // tailnetLocalStatus
    // signNode
    // verifyDeepling
    // ping
    // setTailFSFileServerAddress
    init {
        Log.d("LocalApiClient", "LocalApiClient created")
    }
}
