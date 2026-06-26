// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.tailscale.ipn.util.BrowserOpener
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.util.TdPayload

// Handles taps on Taildrop tdpayload notifications: URL → browser, text → clipboard.
class TdPayloadActionReceiver : BroadcastReceiver() {
  companion object {
    private const val TAG = "TdPayloadActionReceiver"
    const val ACTION_CONSUME = "com.tailscale.ipn.TDPAYLOAD_CONSUME"
    const val EXTRA_KIND = "kind"
    const val EXTRA_CONTENT = "content"
    const val EXTRA_ID = "id"
  }

  override fun onReceive(context: Context, intent: Intent) {
    val kindRaw = intent.getStringExtra(EXTRA_KIND) ?: return
    val content = intent.getStringExtra(EXTRA_CONTENT) ?: return
    val id = intent.getStringExtra(EXTRA_ID)

    val kind =
        runCatching { TdPayload.Kind.valueOf(kindRaw.uppercase()) }
            .getOrNull()
            ?: run {
              TSLog.w(TAG, "unknown tdpayload kind: $kindRaw")
              return
            }

    when (kind) {
      TdPayload.Kind.URL -> openUrl(context, content)
      TdPayload.Kind.TEXT -> copyToClipboard(context, content)
    }

    if (id != null) {
      com.tailscale.ipn.ui.notifier.Notifier.removeTdPayload(id)
    }
  }

  private fun openUrl(context: Context, content: String) {
    val uri =
        runCatching { Uri.parse(content) }
            .getOrNull()
            ?.takeIf { !it.scheme.isNullOrEmpty() }
    if (uri == null) {
      copyToClipboard(context, content)
      return
    }
    if (!BrowserOpener.openInDefaultBrowser(context, uri)) {
      TSLog.w(TAG, "failed to open URL $content")
      copyToClipboard(context, content)
    }
  }

  private fun copyToClipboard(context: Context, content: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Tailscale", content))
    Toast.makeText(context, R.string.taildrop_copied_to_clipboard, Toast.LENGTH_SHORT).show()
  }
}
