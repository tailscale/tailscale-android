// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import android.util.Log
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Ipn.Notify
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

// Notifier is a wrapper around the IPN Bus notifier.  It provides a way to watch
// for changes in various parts of the Tailscale engine.  You will typically only use
// a single Notifier per instance of your application which lasts for the lifetime of
// the process.
//
// The primary entry point here is watchIPNBus which will start a watcher on the IPN bus
// and return you the session Id.  When you are done with your watcher, you must call
// unwatchIPNBus with the sessionId.
object Notifier {
    private val TAG = Notifier::class.simpleName
    private val decoder = Json { ignoreUnknownKeys = true }
    private val isReady = CompletableDeferred<Boolean>()

    val state: StateFlow<Ipn.State> = MutableStateFlow(Ipn.State.NoState)
    val netmap: StateFlow<Netmap.NetworkMap?> = MutableStateFlow(null)
    val prefs: StateFlow<Ipn.Prefs?> = MutableStateFlow(null)
    val engineStatus: StateFlow<Ipn.EngineStatus?> = MutableStateFlow(null)
    val tailFSShares: StateFlow<Map<String, String>?> = MutableStateFlow(null)
    val browseToURL: StateFlow<String?> = MutableStateFlow(null)
    val loginFinished: StateFlow<String?> = MutableStateFlow(null)
    val version: StateFlow<String?> = MutableStateFlow(null)

    // Called by the backend when the localAPI is ready to accept requests.
    @JvmStatic
    @Suppress("unused")
    fun onReady() {
        isReady.complete(true)
        Log.d(TAG, "Ready")
    }

    fun start(scope: CoroutineScope) {
        Log.d(TAG, "Starting")
        scope.launch(Dispatchers.IO) {
            // Wait for the notifier to be ready
            isReady.await()
            val mask =
                NotifyWatchOpt.Netmap.value or NotifyWatchOpt.Prefs.value or NotifyWatchOpt.InitialState.value
            startIPNBusWatcher(mask)
            Log.d(TAG, "Stopped")
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping")
        stopIPNBusWatcher()
    }

    // Callback from jni when a new notification is received
    @OptIn(ExperimentalSerializationApi::class)
    @JvmStatic
    @Suppress("unused")
    fun onNotify(notification: ByteArray) {
        val notify = decoder.decodeFromStream<Notify>(notification.inputStream())
        notify.State?.let { state.set(Ipn.State.fromInt(it)) }
        notify.NetMap?.let(netmap::set)
        notify.Prefs?.let(prefs::set)
        notify.Engine?.let(engineStatus::set)
        notify.TailFSShares?.let(tailFSShares::set)
        notify.BrowseToURL?.let(browseToURL::set)
        notify.LoginFinished?.let { loginFinished.set(it.property) }
        notify.Version?.let(version::set)
    }

    // Starts watching the IPN Bus. This is blocking.
    private external fun startIPNBusWatcher(mask: Int)

    // Stop watching the IPN Bus. This is non-blocking.
    private external fun stopIPNBusWatcher()

    // NotifyWatchOpt is a bitmask of options supplied to the notifier to specify which
    // what we want to see on the Notify bus
    private enum class NotifyWatchOpt(val value: Int) {
        EngineUpdates(1), InitialState(2), Prefs(4), Netmap(8), NoPrivateKey(16), InitialTailFSShares(
            32
        )
    }
}
