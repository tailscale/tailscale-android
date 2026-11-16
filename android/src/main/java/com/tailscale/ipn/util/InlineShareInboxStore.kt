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
// Use UninitializedApp.get() rather than App.get() to avoid re-entering app
// initialization when Notifier starts before App.initOnce() completes.
object InlineShareInboxStore {
  private const val PREF_KEY_INBOX = "inline_share_inbox"
  private val json = Json { ignoreUnknownKeys = true }

  private fun encryptedPrefs() = (UninitializedApp.get() as App).getEncryptedPrefs()

  fun save(inbox: List<PendingInlineShare>): Boolean {
    return try {
      encryptedPrefs().edit().putString(PREF_KEY_INBOX, json.encodeToString(inbox)).commit()
    } catch (e: Exception) {
      TSLog.w("InlineShareInboxStore", "save failed: $e")
      false
    }
  }

  // null means storage was unavailable; do not treat it as an empty inbox.
  fun load(): List<PendingInlineShare>? {
    return try {
      val prefs = encryptedPrefs()
      val raw = prefs.getString(PREF_KEY_INBOX, null) ?: return emptyList()
      json.decodeFromString(raw)
    } catch (e: Exception) {
      TSLog.w("InlineShareInboxStore", "load failed: $e")
      null
    }
  }
}
