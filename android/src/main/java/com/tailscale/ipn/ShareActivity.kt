// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.tailscale.ipn.ui.model.FileTransfer
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.view.TaildropView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ShareActivity is the entry point for Taildrop share intents
class ShareActivity : ComponentActivity() {
  private val TAG = ShareActivity::class.simpleName

  private val transfers: StateFlow<List<FileTransfer>> = MutableStateFlow(emptyList())

  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    setContent { AppTheme { TaildropView(transfers) } }
  }

  override fun onStart() {
    super.onStart()
    Notifier.start(lifecycleScope)
    loadFiles()
  }

  override fun onStop() {
    super.onStop()
    Notifier.stop()
  }

  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    setIntent(intent)
    loadFiles()
  }

  // Loads the files from the intent.
  fun loadFiles() {
    if (intent == null) {
      Log.e(TAG, "Share failure - No intent found")
      return
    }

    val act = intent.action
    val uris: List<Uri?>?

    uris =
        when (act) {
          Intent.ACTION_SEND -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              listOf(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            } else {
              listOf(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            }
          }
          Intent.ACTION_SEND_MULTIPLE -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              intent.getParcelableArrayListExtra<Uri?>(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
              intent.getParcelableArrayListExtra<Uri?>(Intent.EXTRA_STREAM)
            }
          }
          else -> {
            Log.e(TAG, "No extras found in intent - nothing to share")
            null
          }
        }

    val pendingFiles: List<FileTransfer> =
        uris?.filterNotNull()?.mapNotNull {
          contentResolver?.query(it, null, null, null, null)?.let { c ->
            val nameCol = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndex(OpenableColumns.SIZE)
            c.moveToFirst()
            val name = c.getString(nameCol)
            val size = c.getLong(sizeCol)
            c.close()
            FileTransfer(name, size, it)
          }
        } ?: emptyList()

    if (pendingFiles.isEmpty()) {
      Log.e(TAG, "Share failure - no files extracted from intent")
    }

    transfers.set(pendingFiles)
  }
}
