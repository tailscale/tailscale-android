// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import com.tailscale.ipn.R
import com.tailscale.ipn.util.InlineShare
import com.tailscale.ipn.util.PendingInlineShare

sealed class PendingTaildropItem {
  abstract val id: String

  data class File(val partial: Ipn.PartialFile) : PendingTaildropItem() {
    override val id: String =
        partial.FinalPath ?: partial.PartialPath ?: "${partial.Name}-${partial.Started}"
  }

  data class InlineShareItem(val share: PendingInlineShare) : PendingTaildropItem() {
    override val id: String = share.id
  }

  val icon: Int
    get() =
        when (this) {
          is File -> R.drawable.single_file
          is InlineShareItem ->
              if (share.kind == InlineShare.Kind.URL) R.drawable.link else R.drawable.single_file
        }
}
