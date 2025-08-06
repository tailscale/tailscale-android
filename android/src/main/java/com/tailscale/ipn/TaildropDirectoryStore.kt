// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.net.Uri
import com.tailscale.ipn.util.TSLog
import java.io.IOException
import java.security.GeneralSecurityException

object TaildropDirectoryStore {
  // Key to store the SAF URI in EncryptedSharedPreferences.
  val PREF_KEY_SAF_URI = "saf_directory_uri"

  @Throws(IOException::class, GeneralSecurityException::class)
  fun saveFileDirectory(directoryUri: Uri) {
    val prefs = App.get().getEncryptedPrefs()
    prefs.edit().putString(PREF_KEY_SAF_URI, directoryUri.toString()).commit()
  }

  @Throws(IOException::class, GeneralSecurityException::class)
  fun loadSavedDir(): Uri? {
    val prefs = App.get().getEncryptedPrefs()
    val uriString = prefs.getString(PREF_KEY_SAF_URI, null) ?: return null

    return try {
      Uri.parse(uriString)
    } catch (e: Exception) {
      // Malformed URI in prefs ‑‑ log and wipe the bad value
      TSLog.w("MainActivity", "loadSavedDir: invalid URI in prefs: $uriString; clearing")
      prefs.edit().remove(PREF_KEY_SAF_URI).apply()
      null
    }
  }
}
