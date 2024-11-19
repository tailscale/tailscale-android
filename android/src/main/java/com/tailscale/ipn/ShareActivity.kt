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
import androidx.lifecycle.lifecycleScope
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.util.universalFit
import com.tailscale.ipn.ui.view.TaildropView
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    lifecycleScope.launch { withContext(Dispatchers.IO) { loadFiles() } }
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
        uris?.filterNotNull()?.mapNotNull { uri ->
            contentResolver?.query(uri, null, null, null, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
    
                if (cursor.moveToFirst()) {
                    val name: String = cursor.getString(nameCol)
                        ?: generateFallbackName(uri)
                    val size: Long = cursor.getLong(sizeCol)
                    Ipn.OutgoingFile(Name = name, DeclaredSize = size).apply {
                        this.uri = uri
                    }
                } else {
                    TSLog.e(TAG, "Cursor is empty for URI: $uri")
                    null
                }
            }
        } ?: emptyList()    

    if (pendingFiles.isEmpty()) {
      TSLog.e(TAG, "Share failure - no files extracted from intent")
    }

    requestedTransfers.set(pendingFiles)
  }

  private fun generateFallbackName(uri: Uri): String {
    val randomId = Random.nextLong()
    val mimeType = contentResolver?.getType(uri)
    val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
    return if (extension != null) "$randomId.$extension" else randomId.toString()
}
}
