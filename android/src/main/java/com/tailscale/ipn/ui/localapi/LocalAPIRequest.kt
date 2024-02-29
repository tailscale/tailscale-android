// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.localapi

import com.tailscale.ipn.ui.model.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

enum class LocalAPIEndpoint(val rawValue: String) {
    Debug("debug"),
    Debug_Log("debug-log"),
    BugReport("bugreport"),
    Prefs("prefs"),
    FileTargets("file-targets"),
    UploadMetrics("upload-client-metrics"),
    Start("start"),
    LoginInteractive("login-interactive"),
    ResetAuth("reset-auth"),
    Logout("logout"),
    Profiles("profiles/"),
    ProfilesCurrent("profiles/current"),
    Status("status"),
    TKAStatus("tka/status"),
    TKASitng("tka/sign"),
    TKAVerifyDeeplink("tka/verify-deeplink"),
    Ping("ping"),
    Files("files"),
    FilePut("file-put"),
    TailFSServerAddress("tailfs/fileserver-address");

    val prefix = "/localapi/v0/"

    fun path(): String {
        return prefix + rawValue
    }
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
        val path: String,
        val method: String,
        val body: String? = null,
        val responseHandler: (Result<T>) -> Unit,
        val parser: (String) -> Unit,
) {
    companion object {
        val cookieLock = Any()
        var cookieCounter: Int = 0
        val decoder = Json { ignoreUnknownKeys = true }

        fun getCookie(): String {
            synchronized(cookieLock) {
                cookieCounter += 1
                return cookieCounter.toString()
            }
        }

        fun status(responseHandler: StatusResponseHandler): LocalAPIRequest<IpnState.Status> {
            val path = LocalAPIEndpoint.Status.path()
            return LocalAPIRequest<IpnState.Status>(path, "GET", null, responseHandler) { resp ->
                responseHandler(decode<IpnState.Status>(resp))
            }
        }

        fun bugReportId(responseHandler: BugReportIdHandler): LocalAPIRequest<BugReportID> {
            val path = LocalAPIEndpoint.BugReport.path()
            return LocalAPIRequest<BugReportID>(path, "POST", null, responseHandler) { resp ->
                responseHandler(parseString(resp))
            }
        }

        fun prefs(responseHandler: PrefsHandler): LocalAPIRequest<Ipn.Prefs> {
            val path = LocalAPIEndpoint.Prefs.path()
            return LocalAPIRequest<Ipn.Prefs>(path, "GET", null, responseHandler) { resp ->
                responseHandler(decode<Ipn.Prefs>(resp))
            }
        }

        fun profiles(responseHandler: (Result<List<IpnLocal.LoginProfile>>) -> Unit): LocalAPIRequest<List<IpnLocal.LoginProfile>> {
            val path = LocalAPIEndpoint.Profiles.path()
            return LocalAPIRequest<List<IpnLocal.LoginProfile>>(path, "GET", null, responseHandler) { resp ->
                responseHandler(decode<List<IpnLocal.LoginProfile>>(resp))
            }
        }

        fun currentProfile(responseHandler: (Result<IpnLocal.LoginProfile>) -> Unit): LocalAPIRequest<IpnLocal.LoginProfile> {
            val path = LocalAPIEndpoint.ProfilesCurrent.path()
            return LocalAPIRequest<IpnLocal.LoginProfile>(path, "GET", null, responseHandler) { resp ->
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
