// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import android.util.Log
import com.tailscale.ipn.ui.model.Ipn.Notify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

typealias NotifierCallback = (Notify) -> Unit


class Watcher(
        val sessionId: String,
        val mask: Int,
        val callback: NotifierCallback
)

// Notifier is a wrapper around the IPN Bus notifier.  It provides a way to watch
// for changes in various parts of the Tailscale engine.  You will typically only use
// a single Notifier per instance of your application which lasts for the lifetime of
// the process.
//
// The primary entry point here is watchIPNBus which will start a watcher on the IPN bus
// and return you the session Id.  When you are done with your watcher, you must call
// unwatchIPNBus with the sessionId.
class Notifier() {

    // (jonathan) TODO: We should be using a lifecycle aware scope here
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // NotifyWatchOpt is a bitmask of options supplied to the notifier to specify which
    // what we want to see on the Noitfy bus
    enum class NotifyWatchOpt(val value: Int) {
        engineUpdates(0),
        initialState(1),
        prefs(2),
        netmap(4),
        noPrivateKey(8),
        initialTailFSShares(16)
    }

    companion object {
        private val sessionIdLock = Any()
        private var sessionId: Int = 0
        private val decoder = Json { ignoreUnknownKeys = true }
        private val isReady = CompletableDeferred<Boolean>()

        // Called by the backend when the localAPI is ready to accept requests.
        @JvmStatic
        fun onReady() {
            isReady.complete(true)
            Log.d("Notifier", "Notifier is ready")
        }

        private fun generateSessionId(): String {
            synchronized(sessionIdLock) {
                sessionId += 1
                return sessionId.toString()
            }
        }
    }

    // Starts an IPN Bus watcher.  **This is blocking** and will not return until
    // the watcher is stopped and must be executed in a suitable coroutine scope such
    // as Dispatchers.IO 
    private external fun startIPNBusWatcher(sessionId: String, mask: Int)

    // Stops an IPN Bus watcher
    private external fun stopIPNBusWatcher(sessionId: String)

    private var watchers = HashMap<String, Watcher>()

    // Callback from jni when a new notification is received
    fun onNotify(notification: String, sessionId: String) {
        val notify = decoder.decodeFromString<Notify>(notification)
        val watcher = watchers[sessionId]
        watcher?.let { watcher.callback(notify) }
                ?: { Log.e("Notifier", "Received notification for unknown session: ${sessionId}") }
    }

    // Watch the IPN bus for notifications
    // Notifications will be passed to the caller via the callback until
    // the caller calls unwatchIPNBus with the sessionId returned from this call.
    fun watchIPNBus(mask: Int, callback: NotifierCallback): String {
        val sessionId = generateSessionId()
        val watcher = Watcher(sessionId, mask, callback)
        watchers[sessionId] = watcher
        scope.launch {
            // Wait for the notifier to be ready
            isReady.await()
            Log.d("Notifier", "Starting IPN Bus watcher for sessionid: ${sessionId}")
            startIPNBusWatcher(sessionId, mask)
            watchers.remove(sessionId)
            Log.d("Notifier", "IPN Bus watcher for sessionid:${sessionId} has halted")
        }
        return sessionId
    }

    // Cancels the watcher with the given sessionId. No errors are thrown or
    // indicated for invalid sessionIds.
    fun unwatchIPNBus(sessionId: String) {
        stopIPNBusWatcher(sessionId)
    }

    // Cancels all watchers
    fun cancelAllWatchers() {
        for (sessionId in watchers.values.map({ it.sessionId })) {
            unwatchIPNBus(sessionId)
        }
    }

    // Returns a list of all active watchers
    fun watchers(): List<Watcher> {
        return watchers.values.toList()
    }

    // Convenience methods for watching specific parts of the IPN bus

    fun watchNetMap(callback: NotifierCallback): String {
        return watchIPNBus(NotifyWatchOpt.netmap.value, callback)
    }

    fun watchPrefs(callback: NotifierCallback): String {
        return watchIPNBus(NotifyWatchOpt.prefs.value, callback)
    }

    fun watchEngineUpdates(callback: NotifierCallback): String {
        return watchIPNBus(NotifyWatchOpt.engineUpdates.value, callback)
    }

    fun watchAll(callback: NotifierCallback): String {
        return watchIPNBus(
                NotifyWatchOpt.netmap.value or
                        NotifyWatchOpt.prefs.value or
                        NotifyWatchOpt.initialState.value,
                callback
        )
    }

    init {
        Log.d("Notifier", "Notifier created")
    }
}

