// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.util

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable

// Wire format:
//   <md5-of-content-hex>.txt   raw UTF-8 text
//   <md5-of-content-hex>.url   Windows Internet Shortcut INI (CRLF)
data class InlineShare(
    val kind: Kind,
    val content: String,
) {
  @Serializable
  enum class Kind(val extension: String) {
    URL("url"),
    TEXT("txt"),
  }

  fun encoded(): ByteArray =
      when (kind) {
        Kind.TEXT -> content.toByteArray(Charsets.UTF_8)
        // CRLF: Windows-native format; some third-party parsers reject LF-only.
        Kind.URL -> "[InternetShortcut]\r\nURL=$content\r\n".toByteArray(Charsets.UTF_8)
      }

  companion object {
    private val finalNameRegex = Regex("^[0-9a-f]{32}\\.(txt|url)$", RegexOption.IGNORE_CASE)

    fun matches(filename: String): Boolean = finalNameRegex.matches(filename)

    fun decode(filename: String, data: ByteArray): InlineShare? {
      val ext = filename.substringAfterLast('.', "").lowercase()
      return when (ext) {
        "txt" -> {
          val text = runCatching { data.toString(Charsets.UTF_8) }.getOrNull().orEmpty()
          if (text.isEmpty()) null else InlineShare(Kind.TEXT, text)
        }
        "url" -> parseInternetShortcut(data)?.let { InlineShare(Kind.URL, it) }
        else -> null
      }
    }

    private fun parseInternetShortcut(data: ByteArray): String? {
      val text = runCatching { data.toString(Charsets.UTF_8) }.getOrNull() ?: return null
      val start = text.indexOf("URL=", ignoreCase = true).takeIf { it >= 0 } ?: return null
      val after = text.substring(start + 4)
      val end = after.indexOfAny(charArrayOf('\r', '\n')).takeIf { it >= 0 } ?: after.length
      return after.substring(0, end).trim().takeIf { it.isNotEmpty() }
    }

    fun suggestedFilename(kind: Kind, content: String): String =
        "${md5Hex(content)}.${kind.extension}"

    fun writeToCache(context: Context, kind: Kind, content: String): File {
      val share = InlineShare(kind, content)
      val file = File(context.cacheDir, suggestedFilename(kind, content))
      file.writeBytes(share.encoded())
      return file
    }

    private fun md5Hex(input: String): String {
      val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
      return digest.joinToString(separator = "") { b -> "%02x".format(b) }
    }
  }
}

@Serializable
data class PendingInlineShare(
    val id: String = UUID.randomUUID().toString(),
    val kind: InlineShare.Kind,
    val content: String,
    val received: Long = System.currentTimeMillis(),
    // SAF URI of the saved file: backs the open-file fallback and delete-on-open for URLs.
    val uri: String? = null,
)
