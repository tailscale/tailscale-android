// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import com.tailscale.ipn.util.TSLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small, bounded, durable log for the watchdog's decisions.
 *
 * logcat is a 512 KiB ring buffer that rotates in minutes on a busy device, so a
 * decision made at 03:00 is gone by morning. This keeps the watchdog's own
 * history in the app's files directory, where `run-as` can read it and where it
 * survives until the next rotation.
 *
 * @param file the log file, normally `filesDir/watchdog.log`.
 * @param maxBytes rotation threshold. A decision line is roughly 100 bytes, so
 *   64 KiB holds about 650 lines — a little over two days at the default
 *   five-minute cadence. One previous file is kept, so the worst case on disk is
 *   twice this.
 */
internal class WatchdogLog(
    private val file: File,
    private val maxBytes: Long = 64 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
) {
  private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

  @Synchronized
  fun append(message: String) {
    try {
      if (file.length() > maxBytes) rotate()
      file.appendText("${stamp.format(Date(clock()))} $message\n")
    } catch (e: Exception) {
      // Never let logging break the watchdog.
      TSLog.e(TAG, "append failed: $e")
    }
  }

  private fun rotate() {
    val previous = File(file.parentFile, file.name + ".1")
    if (previous.exists()) previous.delete()
    file.renameTo(previous)
  }

  companion object {
    private const val TAG = "TSWatchdog"
  }
}
