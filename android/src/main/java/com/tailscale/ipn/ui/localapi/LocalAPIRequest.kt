// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.localapi

import com.tailscale.ipn.ui.model.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

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

// Potential local and upstream errors.   Error handling in localapi in the go layer
// is inconsistent but different clients already deal with that inconsistency so
// 'fixing' it will likely break other things.
//
// For now, anything that isn't an { error: "message" } will be passed along
// as UNPARSEABLE_RESPONSE.  We can add additional error translation in the parseError
// method as needed.
//
// (jonathan) TODO: Audit local API for all of the possible error results and clean
// it up if possible.
enum class APIErrorVals(val rawValue: String) {
    UNPARSEABLE_RESPONSE("Unparseable localAPI response"),
    NOT_READY("Not Ready");

    fun toError(): Error {
        return Error(rawValue)
    }
}

class LocalAPIRequest<T>(
        path: String,
        val method: String,
        val body: String? = null,
        val parser: (String) -> Unit,
) {
    val path = "/localapi/v0/$path"

    companion object {
        val cookieLock = Any()
        var cookieCounter: Int = 0
        val decoder = Json { ignoreUnknownKeys = true }

        fun <T> get(path: String, body: String? = null, parser: (String) -> Unit) =
            LocalAPIRequest<T>(
                method = "GET",
                path = path,
                body = body,
                parser = parser
            )

        fun <T> put(path: String, body: String? = null, parser: (String) -> Unit) =
            LocalAPIRequest<T>(
                method = "PUT",
                path = path,
                body = body,
                parser = parser
            )

        fun <T> post(path: String, body: String? = null, parser: (String) -> Unit) =
            LocalAPIRequest<T>(
                method = "POST",
                path = path,
                body = body,
                parser = parser
            )

        fun getCookie(): String {
            synchronized(cookieLock) {
                cookieCounter += 1
                return cookieCounter.toString()
            }
        }

        fun status(responseHandler: StatusResponseHandler): LocalAPIRequest<IpnState.Status> {
            return get(Endpoint.STATUS) { resp ->
                responseHandler(decode<IpnState.Status>(resp))
            }
        }

        fun bugReportId(responseHandler: BugReportIdHandler): LocalAPIRequest<BugReportID> {
            return post(Endpoint.BUG_REPORT) { resp ->
                responseHandler(parseString(resp))
            }
        }

        fun prefs(responseHandler: PrefsHandler): LocalAPIRequest<Ipn.Prefs> {
            return get(Endpoint.PREFS) { resp ->
                responseHandler(decode<Ipn.Prefs>(resp))
            }
        }

        fun profiles(responseHandler: (Result<List<IpnLocal.LoginProfile>>) -> Unit): LocalAPIRequest<List<IpnLocal.LoginProfile>> {
            return get(Endpoint.PROFILES) { resp ->
                responseHandler(decode<List<IpnLocal.LoginProfile>>(resp))
            }
        }

        fun currentProfile(responseHandler: (Result<IpnLocal.LoginProfile>) -> Unit): LocalAPIRequest<IpnLocal.LoginProfile> {
            return get(Endpoint.PROFILES_CURRENT) { resp ->
                responseHandler(decode<IpnLocal.LoginProfile>(resp))
            }
        }

        // Check if the response was a generic error
        fun parseError(respData: String): Error {
            try {
                val err = Json.decodeFromString<Errors.GenericError>(respData)
                return Error(err.error)
            } catch (e: Exception) {
                return Error(APIErrorVals.UNPARSEABLE_RESPONSE.toError())
            }
        }

        // Handles responses that are raw strings.  Returns an error result if the string
        // is empty
        fun parseString(respData: String): Result<String> {
            return if (respData.length > 0) Result(respData)
            else Result(APIErrorVals.UNPARSEABLE_RESPONSE.toError())
        }

        // Attempt to decode the response into the expected type.  If that fails, then try
        // parsing as an error.
        inline fun <reified T> decode(respData: String): Result<T> {
            try {
                val message = decoder.decodeFromString<T>(respData)
                return Result(message)
            } catch (e: Exception) {
                return Result(parseError(respData))
            }
        }
    }

    val cookie: String = getCookie()
}
