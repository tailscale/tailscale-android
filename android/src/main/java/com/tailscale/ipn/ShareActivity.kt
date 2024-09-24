// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.util.universalFit
import com.tailscale.ipn.ui.view.TaildropView
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

// ShareActivity is the entry point for Taildrop share intents
class ShareActivity : ComponentActivity() {
  private val TAG = ShareActivity::class.simpleName

  private val requestedTransfers: StateFlow<List<Ipn.OutgoingFile>> = MutableStateFlow(emptyList())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      AppTheme {
        Surface(color = MaterialTheme.colorScheme.inverseSurface) { // Background for the letterbox
          Surface(modifier = Modifier.universalFit()) {
            TaildropView(requestedTransfers, (application as App).applicationScope)
          }
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    // Ensure our app instance is initialized
    App.get()
    loadFiles()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    loadFiles()
  }

  // Loads the files from the intent.
  fun loadFiles() {
    if (intent == null) {
      TSLog.e(TAG, "Share failure - No intent found")
      return
    }

    val act = intent.action

    val uris: List<Uri?>? =
        when (act) {
          Intent.ACTION_SEND -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              listOf(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            } else {
              @Suppress("DEPRECATION")
              listOf(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            }
          }
          Intent.ACTION_SEND_MULTIPLE -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              intent.getParcelableArrayListExtra<Uri?>(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
              @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri?>(Intent.EXTRA_STREAM)
            }
          }
          else -> {
            TSLog.e(TAG, "No extras found in intent - nothing to share")
            null
          }
        }

    val pendingFiles: List<Ipn.OutgoingFile> =
        uris?.filterNotNull()?.mapNotNull {
          contentResolver?.query(it, null, null, null, null)?.let { c ->
            val nameCol = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndex(OpenableColumns.SIZE)
            c.moveToFirst()
            val name: String =
                c.getString(nameCol)
                    ?: run {
                      // For some reason, some content resolvers don't return a name.
                      // Try to build a name from a random integer plus file extension
                      // (if type can be determined), else just a random integer.
                      val rand = Random.nextLong()
                      contentResolver.getType(it)?.let { mimeType ->
                        MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.let {
                            extension ->
                          "$rand.$extension"
                        } ?: "$rand"
                      } ?: "$rand"
                    }
            val size = c.getLong(sizeCol)
            c.close()
            val file = Ipn.OutgoingFile(Name = name, DeclaredSize = size)
            file.uri = it
            file
          }
        } ?: emptyList()

    if (pendingFiles.isEmpty()) {
      TSLog.e(TAG, "Share failure - no files extracted from intent")
    }

    requestedTransfers.set(pendingFiles)
  }
}
