// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.tailscale.ipn.R

// Acting on an inline share: URL opens the browser, text goes to the clipboard, with the
// saved file as the fallback for both. Shared by the notification receiver and the UI so
// the two can't drift.
object InlineShareConsumer {
  private const val TAG = "InlineShareConsumer"

  // Returns true if the share was acted on and its inbox entry should be cleared.
  fun consume(context: Context, share: PendingInlineShare): Boolean =
      when (share.kind) {
        InlineShare.Kind.URL -> consumeUrl(context, share)
        InlineShare.Kind.TEXT ->
            copyToClipboard(context, share.content) || openSavedFile(context, share)
      }

  // Dismissing a URL drops its file too: Android has no handler for .url, so keeping one
  // around serves nobody. Text is always kept, since the file is the point.
  fun dismiss(context: Context, share: PendingInlineShare) {
    if (share.kind == InlineShare.Kind.URL) deleteSavedFile(context, share)
  }

  private fun consumeUrl(context: Context, share: PendingInlineShare): Boolean {
    val uri =
        runCatching { Uri.parse(share.content) }.getOrNull()?.takeIf { !it.scheme.isNullOrEmpty() }
    if (uri != null && BrowserOpener.openInDefaultBrowser(context, uri)) {
      // Opened links have served their purpose, so don't leave the file behind.
      deleteSavedFile(context, share)
      return true
    }
    TSLog.w(TAG, "openUrl failed for ${share.content}")
    return copyToClipboard(context, share.content) || openSavedFile(context, share)
  }

  private fun copyToClipboard(context: Context, content: String): Boolean {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (cm == null) {
      TSLog.w(TAG, "no ClipboardManager")
      return false
    }
    // setPrimaryClip is a binder call: it throws on oversized payloads.
    return runCatching {
          cm.setPrimaryClip(ClipData.newPlainText("Tailscale", content))
          Toast.makeText(context, R.string.taildrop_copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }
        .onFailure { TSLog.w(TAG, "copyToClipboard failed: $it") }
        .isSuccess
  }

  private fun openSavedFile(context: Context, share: PendingInlineShare): Boolean {
    val uri = share.uri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
    val intent =
        Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(uri, "text/plain")
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    return runCatching { context.startActivity(intent) }
        .onFailure { TSLog.w(TAG, "openSavedFile failed: $it") }
        .isSuccess
  }

  private fun deleteSavedFile(context: Context, share: PendingInlineShare) {
    val uri = share.uri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
    runCatching { DocumentFile.fromSingleUri(context, uri)?.delete() }
        .onFailure { TSLog.w(TAG, "deleteSavedFile failed: $it") }
  }
}
