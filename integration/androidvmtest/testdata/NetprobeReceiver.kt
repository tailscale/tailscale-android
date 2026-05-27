// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// NetprobeReceiver is embedded by integration/androidvmtest/android_test.go and compiled into a
// temporary helper APK during the emulator integration test.
//
// The receiver runs that APK's packaged Go netprobe binary from a real Android app process, then
// writes the probe output to the app's private files directory for the Go test to read with adb.
package com.tailscale.ipn.integrationprobe

import android.content.BroadcastReceiver
import android.content.BroadcastReceiver.PendingResult
import android.content.Context
import android.content.Intent
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class NetprobeReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    val path = context.applicationInfo.nativeLibraryDir + "/libnetprobe.so"
    val url = intent.getStringExtra("url")
    Thread { runProbe(context.applicationContext, pending, path, url) }.start()
  }

  private fun runProbe(context: Context, pending: PendingResult, path: String, url: String?) {
    val result =
        try {
          val proc =
              ProcessBuilder(path, requireNotNull(url) { "missing url extra" })
                  .redirectErrorStream(true)
                  .start()
          val buf = ByteArrayOutputStream()
          proc.inputStream.use { it.copyTo(buf) }
          val out = String(buf.toByteArray(), Charsets.UTF_8)
          if (!proc.waitFor(45, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            "timeout\n$out"
          } else {
            "exit=${proc.exitValue()}\n$out"
          }
        } catch (e: Exception) {
          "error=$e\n"
        }

    try {
      File(context.filesDir, "result").writeText(result, Charsets.UTF_8)
    } catch (_: Exception) {}
    pending.finish()
  }
}
