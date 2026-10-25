// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.util

import com.tailscale.ipn.App
import com.tailscale.ipn.UninitializedApp
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Persists the inline-share inbox so pending shares survive process death.
//
// UninitializedApp.get() rather than App.get(): loaded from Notifier's field initializer,
// which runs inside App's initOnce() before isInitialized is set. App.get() would re-enter
// initOnce() and start Libtailscale a second time.
object InlineShareInboxStore {
  private const val PREF_KEY_INBOX = "inline_share_inbox"
  private val json = Json { ignoreUnknownKeys = true }

  private fun encryptedPrefs() = (UninitializedApp.get() as App).getEncryptedPrefs()

  // apply(), not commit(): the saved file is the durable record, this is just UI state.
  fun save(inbox: List<PendingInlineShare>) {
    val encoded = json.encodeToString(inbox)
    runCatching { encryptedPrefs().edit().putString(PREF_KEY_INBOX, encoded).apply() }
        .onFailure { TSLog.w("InlineShareInboxStore", "save failed: $it") }
  }

  fun load(): List<PendingInlineShare> {
    val prefs = encryptedPrefs()
    val raw = prefs.getString(PREF_KEY_INBOX, null) ?: return emptyList()
    return try {
      json.decodeFromString(raw)
    } catch (e: Exception) {
      TSLog.w("InlineShareInboxStore", "load: invalid inbox in prefs; clearing")
      prefs.edit().remove(PREF_KEY_INBOX).apply()
      emptyList()
    }
  }
}
