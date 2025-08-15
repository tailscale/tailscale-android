// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.Context
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
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.util.universalFit
import com.tailscale.ipn.ui.view.TaildropView
import com.tailscale.ipn.util.TSLog
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    lifecycleScope.launch {
      loadFiles()
    }
  }

  // Loads the files from the intent.
  suspend fun loadFiles() {
    if (intent == null) {
      TSLog.e(TAG, "Share failure - No intent found")
      return
    }

    val act = intent.action

    val uris: List<Uri> =
        when (act) {
          Intent.ACTION_SEND -> {
            if (intent.extras?.containsKey(Intent.EXTRA_STREAM) == true) {
              // If EXTRA_STREAM is present, get the single URI for that stream
              listOfNotNull(intent.versionSafeGetStreamUri())
            }
            else if (intent.extras?.containsKey(Intent.EXTRA_TEXT) == true) {
              // If EXTRA_TEXT is present, create a temporary file with the text content.
              // This could be any shared text, like a URL or plain text from the clipboard.
              val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
              val uri = createTemporaryFile(text)
              listOf(uri)
            }
            else {
              TSLog.e(TAG, "No extras found in intent - nothing to share")
              emptyList()
            }
          }
          Intent.ACTION_SEND_MULTIPLE -> {
            // If ACTION_SEND_MULTIPLE, assume this is a list of files to share
            intent.versionSafeGetStreamUris()
          }
          else -> {
            TSLog.e(TAG, "Unexpected intent action: $act. Expected ACTION_SEND or ACTION_SEND_MULTIPLE")
            emptyList()
          }
        }

    val pendingFiles: List<Ipn.OutgoingFile> =
        uris.mapNotNull { uri ->
          contentResolver?.query(uri, null, null, null, null)?.use { cursor ->
            val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)

            if (cursor.moveToFirst()) {
              val name: String = cursor.getString(nameCol) ?: generateFallbackName(uri)
              val size: Long = cursor.getLong(sizeCol)
              Ipn.OutgoingFile(Name = name, DeclaredSize = size).apply { this.uri = uri }
            } else {
              TSLog.e(TAG, "Cursor is empty for URI: $uri")
              null
            }
          }
        }

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

private fun Intent.versionSafeGetStreamUri(): Uri? =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
  }
  else {
    @Suppress("DEPRECATION")
    getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
  }

private fun Intent.versionSafeGetStreamUris(): List<Uri> =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableArrayListExtra<Uri?>(Intent.EXTRA_STREAM, Uri::class.java)
  } else {
    @Suppress("DEPRECATION")
    getParcelableArrayListExtra<Uri?>(Intent.EXTRA_STREAM)
  }
    ?.filterNotNull()
    ?: emptyList()

/**
 * Creates a temporary txt file in the app's cache directory with the given content.
 * Then grants temporary read permission to the file using FileProvider and returns its URI.
 */
private suspend fun Context.createTemporaryFile(
  content: String,
  dir: File = cacheDir,
  fileName: String = "shared_text_${System.currentTimeMillis()}.txt",
): Uri {
  // Create temporary file in cache directory
  val tempFile = File(dir, fileName)
  withContext(Dispatchers.IO) {
    tempFile.writeText(content)
  }

  // Get content URI using FileProvider
  val uri = FileProvider.getUriForFile(
    this,
    "${applicationContext.packageName}.fileprovider",
    tempFile
  )

  // Grant temporary read permission
  grantUriPermission(
    packageName,
    uri,
    Intent.FLAG_GRANT_READ_URI_PERMISSION
  )

  return uri
}