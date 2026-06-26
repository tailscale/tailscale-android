// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.util

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// JSON envelope for shipping a URL or plain text through Taildrop. Mirrors the
// iOS/macOS TdPayload wire format.
@Serializable
data class TdPayload(
    val version: Int = 1,
    val kind: Kind,
    val content: String,
    val title: String? = null,
) {
  @Serializable
  enum class Kind {
    @SerialName("url") URL,
    @SerialName("text") TEXT,
  }

  fun encoded(): ByteArray = json.encodeToString(this).toByteArray(Charsets.UTF_8)

  companion object {
    // 4 chars to fit OSS rxExtensionSuffix so nextFilename preserves it on rename.
    const val fileExtension = "tdpl"

    // Match Swift JSONEncoder: emit non-Optional defaults (so `version` is on
    // the wire), but skip nil Optionals.
    private val json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      explicitNulls = false
    }

    fun decode(data: ByteArray): TdPayload =
        json.decodeFromString(serializer(), String(data, Charsets.UTF_8))

    fun writeToCache(context: Context, kind: Kind, content: String, title: String? = null): File {
      val payload = TdPayload(kind = kind, content = content, title = title)
      val name = "${shortHash(content)}.$fileExtension"
      val file = File(context.cacheDir, name)
      file.writeBytes(payload.encoded())
      return file
    }

    // FNV-1a 64-bit, base-36. Filename hashes to keep payload bytes off disk in plaintext.
    private fun shortHash(content: String): String {
      var hash = 0xcbf29ce484222325UL
      for (b in content.toByteArray(Charsets.UTF_8)) {
        hash = hash xor (b.toUByte().toULong())
        hash = hash * 0x100000001b3UL
      }
      return hash.toString(36)
    }
  }
}

// In-memory record of a received TdPayload backing the in-app banner / list sheet.
data class PendingTdPayload(
    val id: String = UUID.randomUUID().toString(),
    val kind: TdPayload.Kind,
    val content: String,
    val title: String?,
    val received: Long = System.currentTimeMillis(),
)
