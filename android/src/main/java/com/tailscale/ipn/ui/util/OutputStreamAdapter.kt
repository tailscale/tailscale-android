// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import com.tailscale.ipn.util.TSLog
import java.io.OutputStream

// This class adapts a Java OutputStream to the libtailscale.OutputStream interface.
class OutputStreamAdapter(private val outputStream: OutputStream) : libtailscale.OutputStream {
    // Write the entire buffer.  If the underlying stream throws,
    // gomobile will convert the IOException into a Go error and
    // io.Copy will stop immediately.
    override fun write(data: ByteArray): Long {
        outputStream.write(data)      // may throw IOException=
        return data.size.toLong()     // reached only on success
    }
    override fun close() {
        try { outputStream.flush() } catch (_: Exception) { /* ignore */ }
        outputStream.close()
    }
}

