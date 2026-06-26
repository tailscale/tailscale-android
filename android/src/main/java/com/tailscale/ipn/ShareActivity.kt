// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Patterns
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.util.universalFit
import com.tailscale.ipn.ui.view.TaildropView
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.util.TdPayload
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ShareActivity is the entry point for Taildrop share intents
class ShareActivity : ComponentActivity() {
  private val TAG = ShareActivity::class.simpleName

  private val requestedTransfers: StateFlow<List<Ipn.OutgoingFile>> = MutableStateFlow(emptyList())

  @OptIn(ExperimentalMaterial3Api::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      AppTheme {
        Surface(color = MaterialTheme.colorScheme.inverseSurface) { // Background for the letterbox
          Surface(modifier = Modifier.universalFit()) {
            Scaffold(
                topBar = {
                  TopAppBar(
                      title = { Text(stringResource(R.string.share)) },
                      navigationIcon = {
                        IconButton(onClick = { finish() }) {
                          Icon(
                              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                              contentDescription = stringResource(R.string.back),
                          )
                        }
                      },
                  )
                }) { innerPadding ->
                  Surface(modifier = Modifier.padding(innerPadding)) {
                    TaildropView(requestedTransfers, (application as App).applicationScope)
                  }
                }
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

    val pendingFiles: MutableList<Ipn.OutgoingFile> =
        uris
            ?.filterNotNull()
            ?.mapNotNull { uri ->
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
            ?.toMutableList() ?: mutableListOf()

    if (pendingFiles.isEmpty() && act == Intent.ACTION_SEND) {
      tdPayloadFromIntent(intent)?.let { pendingFiles.add(it) }
    }

    if (pendingFiles.isEmpty()) {
      TSLog.e(TAG, "Share failure - no files extracted from intent")
    }

    requestedTransfers.set(pendingFiles)
  }

  // Wraps text/URL EXTRA_TEXT in a `.tdpl` envelope so it rides the regular
  // Taildrop file pipeline.
  private fun tdPayloadFromIntent(intent: Intent): Ipn.OutgoingFile? {
    val raw = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    if (raw.isEmpty()) return null

    val text = stripChromeHighlightedTextShare(raw)

    val title =
        intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()?.takeIf { it.isNotBlank() }

    val kind = classifyKind(text)

    return try {
      val file = TdPayload.writeToCache(applicationContext, kind, text, title)
      Ipn.OutgoingFile(Name = file.name, DeclaredSize = file.length()).apply {
        this.uri = Uri.fromFile(file)
        this.tdPayload = TdPayload(kind = kind, content = text, title = title)
      }
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to write tdpayload: $e")
      null
    }
  }

  private fun classifyKind(text: String): TdPayload.Kind {
    // Patterns.WEB_URL covers http(s)://, ftp://, www., bare hostnames. Use .matches()
    // so a URL embedded in a sentence stays TEXT.
    if (Patterns.WEB_URL.matcher(text).matches()) {
      val scheme = runCatching { Uri.parse(text) }.getOrNull()?.scheme
      if (scheme != null && scheme != "file") return TdPayload.Kind.URL
    }
    // Opaque schemes (tel:, sms:, mailto:, ssh:, magnet:, geo:, tailscale:, …) have
    // no `//` so Patterns.WEB_URL misses them. Accept anything that's whitespace-free
    // and parses to a non-file scheme with a non-empty body. The whitespace gate keeps
    // "see: this thing" out without us having to maintain a scheme allowlist.
    if (text.none { it.isWhitespace() }) {
      val parsed = runCatching { Uri.parse(text) }.getOrNull()
      val scheme = parsed?.scheme?.lowercase()
      if (scheme != null && scheme != "file" && !parsed.schemeSpecificPart.isNullOrBlank()) {
        return TdPayload.Kind.URL
      }
    }
    return TdPayload.Kind.TEXT
  }

  // Chromium highlighted-text shares come through as `"<snippet>"\n <url>#:~:text=…`.
  // Receivers paste the blob verbatim; drop the URL tail and outer quotes.
  private fun stripChromeHighlightedTextShare(text: String): String {
    val urlLine = Regex("""(?m)^[ \t]*https?://\S*#:~:text=\S*[ \t]*$""").find(text) ?: return text
    val before = text.substring(0, urlLine.range.first).trimEnd()
    val unquoted = before.removeSurrounding("\"")
    return if (unquoted.isNotEmpty()) unquoted else text
  }

  private fun generateFallbackName(uri: Uri): String {
    val randomId = Random.nextLong()
    val mimeType = contentResolver?.getType(uri)
    val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
    return if (extension != null) "$randomId.$extension" else randomId.toString()
  }
}
