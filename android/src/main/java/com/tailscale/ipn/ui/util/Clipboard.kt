// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import android.content.ClipData
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.toClipEntry

suspend fun Clipboard.copyText(label: String, text: String) {
  setClipEntry(ClipData.newPlainText(label, text).toClipEntry())
}
