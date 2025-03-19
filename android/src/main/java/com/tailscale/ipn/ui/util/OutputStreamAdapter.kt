// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import com.tailscale.ipn.util.TSLog
import java.io.OutputStream

// This class adapts a Java OutputStream to the libtailscale.OutputStream interface.
class OutputStreamAdapter(private val outputStream: OutputStream) : libtailscale.OutputStream {
  // writes data to the outputStream in its entirety. Returns -1 on error.
  override fun write(data: ByteArray): Long {
    return try {
      outputStream.write(data)
      outputStream.flush()
      data.size.toLong()
    } catch (e: Exception) {
      TSLog.d("OutputStreamAdapter", "write exception: $e")
      -1L
    }
  }

  override fun close() {
    outputStream.close()
  }
}
