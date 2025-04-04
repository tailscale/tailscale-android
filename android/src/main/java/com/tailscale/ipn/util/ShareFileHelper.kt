// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import libtailscale.Libtailscale
import java.util.concurrent.ConcurrentHashMap

// Define a top-level data class in a proper package (if needed by gomobile)
data class SafFile(val fd: Int, val uri: String)

// This object implements libtailscale.ShareFileHelper.
object ShareFileHelper : libtailscale.ShareFileHelper {
  private var appContext: Context? = null
  private var savedUri: String? = null

  // Cache results keyed by filename, so the file creation is done only once per file.
  private val fileCache = ConcurrentHashMap<String, SafFile>()

  @JvmStatic
  fun init(context: Context, uri: String) {
    appContext = context.applicationContext
    savedUri = uri
    Libtailscale.setShareFileHelper(this)
  }

  private fun createFileCached(fileName: String): SafFile {
    // Use a composite key that includes both fileName and savedUri.
    val key = "$fileName|$savedUri"
    return fileCache.getOrPut(key) {
      val context = appContext ?: return SafFile(-1, "")
      val directoryUriString = savedUri ?: return SafFile(-1, "")
      val uri = Uri.parse(directoryUriString)
      val pickedDir = DocumentFile.fromTreeUri(context, uri) ?: return SafFile(-1, "")
      val newFile =
          pickedDir.createFile("application/octet-stream", fileName) ?: return SafFile(-1, "")
      val pfd = context.contentResolver.openFileDescriptor(newFile.uri, "w")
      SafFile(pfd?.fd ?: -1, newFile.uri.toString())
    }
  }

  override fun openFileDescriptor(fileName: String): Int {
    return createFileCached(fileName).fd
  }

  override fun openFileURI(fileName: String): String {
    return createFileCached(fileName).uri
  }

  override fun renamePartialFile(
      partialUri: String,
      targetDirUri: String,
      targetName: String
  ): String {
    try {
      val context = appContext ?: throw IllegalStateException("appContext is null")
      val partialUriObj = Uri.parse(partialUri)
      val targetDirUriObj = Uri.parse(targetDirUri)
      val targetDir =
          DocumentFile.fromTreeUri(context, targetDirUriObj)
              ?: throw IllegalStateException(
                  "Unable to get target directory from URI: $targetDirUri")
      var finalTargetName = targetName

      var destFile = targetDir.findFile(finalTargetName)
      if (destFile != null) {
        finalTargetName = generateNewFilename(finalTargetName)
      }

      destFile =
          targetDir.createFile("application/octet-stream", finalTargetName)
              ?: throw IOException("Failed to create new file with name: $finalTargetName")

      context.contentResolver.openInputStream(partialUriObj)?.use { input ->
        context.contentResolver.openOutputStream(destFile.uri)?.use { output ->
          input.copyTo(output)
        } ?: throw IOException("Unable to open output stream for URI: ${destFile.uri}")
      } ?: throw IOException("Unable to open input stream for URI: $partialUri")

      DocumentFile.fromSingleUri(context, partialUriObj)?.delete()
      return destFile.uri.toString()
    } catch (e: Exception) {
      throw IOException(
          "Failed to rename partial file from URI $partialUri to final file in $targetDirUri with name $targetName: ${e.message}",
          e)
    }
  }

  fun generateNewFilename(filename: String): String {
    val dotIndex = filename.lastIndexOf('.')
    val baseName: String
    val extension: String
    if (dotIndex != -1) {
      baseName = filename.substring(0, dotIndex)
      extension = filename.substring(dotIndex)
    } else {
      baseName = filename
      extension = ""
    }
    val randomSuffix = (1000..9999).random()
    return "$baseName-${System.currentTimeMillis()}-$randomSuffix$extension"
  }
}
