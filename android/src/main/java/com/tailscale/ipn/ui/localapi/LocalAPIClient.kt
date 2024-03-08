// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.localapi

import android.util.Log
import com.tailscale.ipn.ui.model.*
import kotlinx.coroutines.*

// A response from the echo endpoint.
typealias StatusResponseHandler = (Result<IpnState.Status>) -> Unit

typealias BugReportIdHandler = (Result<BugReportID>) -> Unit

typealias PrefsHandler = (Result<Ipn.Prefs>) -> Unit

class LocalApiClient {
    constructor() {
        Log.d("LocalApiClient", "LocalApiClient created")
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
    external fun doRequest(request: String, method: String, body: String, cookie: String)

    fun <T> executeRequest(request: LocalAPIRequest<T>) {
        Log.d("LocalApiClient", "Executing request:${request.method}:${request.path}")
        addRequest(request)
        // The jni handler will treat the empty string in the body as null.
        val body = request.body ?: ""
        doRequest(request.path, request.method, body, request.cookie)
    }

    // This is called from the JNI layer to publish localAPIResponses.  This should execute on the
    // same thread that called doRequest.
    fun onResponse(response: String, cookie: String) {
        val request = requests[cookie]
        if (request != null) {
            Log.d("LocalApiClient", "Reponse for request:${request.path} cookie:${request.cookie}")
            // The response handler will invoked internally by the request parser 
            request.parser(response)
            removeRequest(cookie)
        } else {
            Log.e("LocalApiClient", "Received response for unknown request: ${cookie}")
        }
    }

    // Tracks in-flight requests and their callback handlers by cookie. This should
    // always be manipulated via the addRequest and removeRequest methods.
    private var requests = HashMap<String, LocalAPIRequest<*>>()
    private var requestLock = Any()

    fun addRequest(request: LocalAPIRequest<*>) {
        synchronized(requestLock) { requests[request.cookie] = request }
    }

    fun removeRequest(cookie: String) {
        synchronized(requestLock) { requests.remove(cookie) }
    }

    // localapi Invocations

    fun getStatus(responseHandler: StatusResponseHandler) {
        val req = LocalAPIRequest.status(responseHandler)
        executeRequest<IpnState.Status>(req)
    }

    fun getBugReportId(responseHandler: BugReportIdHandler) {
        val req = LocalAPIRequest.bugReportId(responseHandler)
        executeRequest<BugReportID>(req)
    }

    fun getPrefs(responseHandler: PrefsHandler) {
        val req = LocalAPIRequest.prefs(responseHandler)
        executeRequest<Ipn.Prefs>(req)
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
    // profiles
    // currentProfile
    // addProfile
    // switchProfile
    // deleteProfile
    // tailnetLocalStatus
    // signNode
    // verifyDeepling
    // ping
    // setTailFSFileServerAddress

    // Run some tests to validate the APIs work before we have anything
    // that calls them.  This runs after a short delay to avoid not-ready
    // errors
    // (jonathan) TODO: Do we need some kind of "onReady" callback?
    // (jonathan) TODO: Remove these we're further along

    fun runAPITests() = runBlocking {
        delay(5000L)
        getStatus { result ->
            if (result.failed) {
                Log.e("LocalApiClient", "Error getting status: ${result.error}")
            } else {
                val status = result.success
                Log.d("LocalApiClient", "Got status: ${status}")
            }
        }

        getBugReportId { result ->
            if (result.failed) {
                Log.e("LocalApiClient", "Error getting bug report id: ${result.error}")
            } else {
                val bugReportId = result.success
                Log.d("LocalApiClient", "Got bug report id: ${bugReportId}")
            }
        }

        getPrefs { result ->
            if (result.failed) {
                Log.e("LocalApiClient", "Error getting prefs: ${result.error}")
            } else {
                val prefs = result.success
                Log.d("LocalApiClient", "Got prefs: ${prefs}")
            }
        }
    }
}
