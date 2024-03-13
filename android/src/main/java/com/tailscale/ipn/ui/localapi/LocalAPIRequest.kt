// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.localapi

import com.tailscale.ipn.ui.model.BugReportID
import com.tailscale.ipn.ui.model.Errors
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.IpnState
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.util.UUID

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
    NOT_READY("Not Ready"),
    NO_PREFS("Current prefs not available");

    fun toError(): Error {
        return Error(rawValue)
    }
}

class LocalAPIRequest<T>(
        path: String,
        val method: String,
        val body: ByteArray? = null,
        val parser: (ByteArray) -> Unit,
) {
    val path = "/localapi/v0/$path"
    val cookie = UUID.randomUUID().toString()

    companion object {

        val decoder = Json { ignoreUnknownKeys = true }

        fun <T> get(path: String, body: ByteArray? = null, parser: (ByteArray) -> Unit) =
                LocalAPIRequest<T>(
                        method = "GET",
                        path = path,
                        body = body,
                        parser = parser
                )

        fun <T> put(path: String, body: ByteArray? = null, parser: (ByteArray) -> Unit) =
                LocalAPIRequest<T>(
                        method = "PUT",
                        path = path,
                        body = body,
                        parser = parser
                )

        fun <T> post(path: String, body: ByteArray? = null, parser: (ByteArray) -> Unit) =
                LocalAPIRequest<T>(
                        method = "POST",
                        path = path,
                        body = body,
                        parser = parser
                )

        fun <T> patch(path: String, body: ByteArray? = null, parser: (ByteArray) -> Unit) =
                LocalAPIRequest<T>(
                        method = "PATCH",
                        path = path,
                        body = body,
                        parser = parser
                )

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

        fun editPrefs(prefs: Ipn.MaskedPrefs, responseHandler: (Result<Ipn.Prefs>) -> Unit): LocalAPIRequest<Ipn.Prefs> {
            val body = Json.encodeToString(prefs).toByteArray()
            return patch(Endpoint.PREFS, body) { resp ->
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

        fun startLoginInteractive(responseHandler: (Result<String>) -> Unit): LocalAPIRequest<String> {
            return post(Endpoint.LOGIN_INTERACTIVE) { resp ->
                responseHandler(parseString(resp))
            }
        }

        fun logout(responseHandler: (Result<String>) -> Unit): LocalAPIRequest<String> {
            return post(Endpoint.LOGOUT) { resp ->
                responseHandler(parseString(resp))
            }
        }

        // Check if the response was a generic error
        @OptIn(ExperimentalSerializationApi::class)
        fun parseError(respData: ByteArray): Error {
            return try {
                val err = Json.decodeFromStream<Errors.GenericError>(respData.inputStream())
                Error(err.error)
            } catch (e: Exception) {
                Error(APIErrorVals.UNPARSEABLE_RESPONSE.toError())
            }
        }

        // Handles responses that are raw strings.  Returns an error result if the string
        // is empty
        private fun parseString(respData: ByteArray): Result<String> {
            return if (respData.isNotEmpty()) Result(respData.decodeToString())
            else Result(APIErrorVals.UNPARSEABLE_RESPONSE.toError())
        }

        // Attempt to decode the response into the expected type.  If that fails, then try
        // parsing as an error.
        @OptIn(ExperimentalSerializationApi::class)
        private inline fun <reified T> decode(respData: ByteArray): Result<T> {
            return try {
                val message = decoder.decodeFromStream<T>(respData.inputStream())
                Result(message)
            } catch (e: Exception) {
                Result(parseError(respData))
            }
        }
    }
}
