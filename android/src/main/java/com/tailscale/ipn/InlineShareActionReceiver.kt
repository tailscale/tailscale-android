// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.notifier.TaildropNotifier
import com.tailscale.ipn.util.InlineShareConsumer

// Handles taps on Taildrop inline-share notifications: URL → browser, text → clipboard.
class InlineShareActionReceiver : BroadcastReceiver() {
  companion object {
    const val ACTION_CONSUME = "com.tailscale.ipn.INLINE_SHARE_CONSUME"
    const val ACTION_DISMISS = "com.tailscale.ipn.INLINE_SHARE_DISMISS"
    const val EXTRA_ID = "id"
  }

  // Looking the share up by id makes this idempotent: whichever of tap or swipe lands
  // second finds no entry and does nothing.
  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.getStringExtra(EXTRA_ID) ?: return
    val share = Notifier.inlineShareInbox.value.find { it.id == id } ?: return
    when (intent.action) {
      ACTION_CONSUME -> {
        // Leave the entry alone if nothing happened, so the user can retry.
        if (!InlineShareConsumer.consume(context, share)) return
        Notifier.removeInlineShare(id)
        TaildropNotifier.cancel(context, id)
      }
      // Swiped away: the notification is already gone, so there's nothing to cancel.
      ACTION_DISMISS -> {
        InlineShareConsumer.dismiss(context, share)
        Notifier.removeInlineShare(id)
      }
    }
  }
}
