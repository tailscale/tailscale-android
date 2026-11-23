// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.integrationprobe

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.File

class ShareFileActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val name = requireNotNull(intent.getStringExtra("name")) { "missing name extra" }
    val content = requireNotNull(intent.getStringExtra("content")) { "missing content extra" }
    File(filesDir, name).writeText(content, Charsets.UTF_8)
    val uri = Uri.parse("content://$packageName.files/$name")
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(name, uri)
    startActivity(Intent.createChooser(send, null))
    finish()
  }
}
