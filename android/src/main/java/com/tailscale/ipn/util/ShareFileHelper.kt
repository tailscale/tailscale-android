// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.tailscale.ipn.TaildropDirectoryStore
import com.tailscale.ipn.ui.util.OutputStreamAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import libtailscale.Libtailscale
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class SafFile(val fd: Int, val uri: String)

object ShareFileHelper : libtailscale.ShareFileHelper {
  private var appContext: Context? = null
  private var app: libtailscale.Application? = null
  private var savedUri: String? = null
  private var scope: CoroutineScope? = null

  @JvmStatic
  fun init(context: Context, app: libtailscale.Application, uri: String, appScope: CoroutineScope) {
    appContext = context.applicationContext
    this.app = app
    savedUri = uri
    scope = appScope
    Libtailscale.setShareFileHelper(this)
    TSLog.d("ShareFileHelper", "init ShareFileHelper with savedUri: $savedUri")
  }
  // A simple data class that holds a SAF OutputStream along with its URI.
  data class SafStream(val uri: String, val stream: OutputStream)

  // Cache for streams; keyed by file name and savedUri.
  private val streamCache = ConcurrentHashMap<String, SafStream>()

  val taildropPrompt = MutableSharedFlow<Unit>(replay = 0)

  fun observeTaildropPrompt(): Flow<Unit> = taildropPrompt

  @Volatile private var directoryReady: CompletableDeferred<Unit>? = null

  // A helper function that creates (or reuses) a SafStream for a given file.
  private fun createStreamCached(fileName: String): SafStream {
    val key = "$fileName|$savedUri"
    return streamCache.getOrPut(key) {
      val context: Context =
          appContext
              ?: run {
                TSLog.e("ShareFileHelper", "appContext is null, cannot create file: $fileName")
                return SafStream("", OutputStream.nullOutputStream())
              }
      val directoryUriString =
          savedUri
              ?: run {
                TSLog.e("ShareFileHelper", "savedUri is null, cannot create file: $fileName")
                return SafStream("", OutputStream.nullOutputStream())
              }
      val dirUri = Uri.parse(directoryUriString)
      val pickedDir: DocumentFile =
          DocumentFile.fromTreeUri(context, dirUri)
              ?: run {
                TSLog.e("ShareFileHelper", "Could not access directory for URI: $dirUri")
                return SafStream("", OutputStream.nullOutputStream())
              }
      val newFile: DocumentFile =
          pickedDir.createFile("application/octet-stream", fileName)
              ?: run {
                TSLog.e("ShareFileHelper", "Failed to create file: $fileName in directory: $dirUri")
                return SafStream("", OutputStream.nullOutputStream())
              }
      // Attempt to open an OutputStream for writing.
      val os: OutputStream? = context.contentResolver.openOutputStream(newFile.uri)
      if (os == null) {
        TSLog.e("ShareFileHelper", "openOutputStream returned null for URI: ${newFile.uri}")
        SafStream(newFile.uri.toString(), OutputStream.nullOutputStream())
      } else {
        TSLog.d("ShareFileHelper", "Opened OutputStream for file: $fileName")
        SafStream(newFile.uri.toString(), os)
      }
    }
  }

  fun hasValidTaildropDir(): Boolean {
    val uri = TaildropDirectoryStore.loadSavedDir()
    if (uri == null) return false

    // Only SAF tree URIs are supported
    if (uri.scheme != "content") {
      TSLog.w("ShareFileHelper", "Invalid URI scheme for taildrop dir: ${uri.scheme}")
      return false
    }

    val context = appContext ?: return false
    val docFile = DocumentFile.fromTreeUri(context, uri)

    if (docFile == null || !docFile.exists() || !docFile.canWrite()) {
      TSLog.w("ShareFileHelper", "Stored taildrop URI is invalid or inaccessible: $uri")
      return false
    }

    return true
  }

  private suspend fun waitUntilTaildropDirReady() {
    if (!hasValidTaildropDir()) {
      if (directoryReady?.isActive != true) {
        directoryReady = CompletableDeferred()
        scope?.launch { taildropPrompt.emit(Unit) }
      }
      directoryReady?.await()
    }
  }

  fun notifyDirectoryReady() {
    directoryReady?.takeIf { !it.isCompleted }?.complete(Unit)
  }

  // This method returns a SafStream containing the SAF URI and its corresponding OutputStream.
  override fun openFileWriter(fileName: String): libtailscale.OutputStream {
    runBlocking { waitUntilTaildropDirReady() }

    val stream = createStreamCached(fileName)
    return OutputStreamAdapter(stream.stream)
  }

  override fun openFileURI(fileName: String): String {
    runBlocking { waitUntilTaildropDirReady() }

    val safFile = createStreamCached(fileName)
    return safFile.uri
  }

  override fun renamePartialFile(
      partialUri: String,
      targetName: String
  ): String {
    try {
      val context = appContext ?: throw IllegalStateException("appContext is null")
      val partialUriObj = Uri.parse(partialUri)

      TSLog.d("ShareFileHelper", "renamePartialFile with uri: $partialUri and dir: $savedUri")

      if (partialUriObj.scheme != "content") {
        throw IllegalArgumentException("Expected SAF URI for partial file, got: $partialUri")
      }

      val targetDir =
          DocumentFile.fromTreeUri(context, Uri.parse(savedUri))
              ?: throw IllegalStateException("Invalid target directory URI: $savedUri")

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
        } ?: throw IOException("Unable to open output stream for URI: $finalTargetName")
      } ?: throw IOException("Unable to open input stream for URI $partialUri")

      DocumentFile.fromSingleUri(context, partialUriObj)?.delete()

      return destFile.uri.toString()
    } catch (e: Exception) {
      throw IOException(
          "Failed to rename partial file from URI $partialUri to final file in $savedUri with name $targetName: ${e.message}",
          e)
    }
  }

  fun generateNewFilename(filename: String): String {
    val dotIndex = filename.lastIndexOf('.')
    val baseName = if (dotIndex != -1) filename.substring(0, dotIndex) else filename
    val extension = if (dotIndex != -1) filename.substring(dotIndex) else ""

    val uuid = UUID.randomUUID()
    return "$baseName-$uuid$extension"
  }

  fun setUri(uri: String) {
    savedUri = uri
  }
}
