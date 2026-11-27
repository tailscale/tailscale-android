// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.integrationprobe

import android.app.Activity
import android.os.Bundle

// LoginPageActivity stands in for a browser: it visits the sign-in page the app opens, then closes.
class LoginPageActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val url = requireNotNull(intent.dataString) { "missing URL" }
    val probe = applicationInfo.nativeLibraryDir + "/libnetprobe.so"
    Thread {
          ProcessBuilder(probe, url).redirectErrorStream(true).start().run {
            inputStream.use { it.readBytes() }
            waitFor()
          }
          runOnUiThread { finish() }
        }
        .start()
  }
}
