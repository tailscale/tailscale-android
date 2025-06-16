// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.tailscale.ipn.ui.util.InputStreamAdapter
import com.tailscale.ipn.ui.util.OutputStreamAdapter
import libtailscale.Libtailscale
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class SafFile(val fd: Int, val uri: String)

object ShareFileHelper : libtailscale.ShareFileHelper {
  private var appContext: Context? = null
  private var savedUri: String? = null

  @JvmStatic
  fun init(context: Context, uri: String) {
    appContext = context.applicationContext
    savedUri = uri
    Libtailscale.setShareFileHelper(this)
  }

  // A simple data class that holds a SAF OutputStream along with its URI.
  data class SafStream(val uri: String, val stream: OutputStream)

  // Cache for streams; keyed by file name and savedUri.
  private val streamCache = ConcurrentHashMap<String, SafStream>()

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

  // This method returns a SafStream containing the SAF URI and its corresponding OutputStream.
  override fun openFileWriter(fileName: String): libtailscale.OutputStream {
    val stream = createStreamCached(fileName)
    return OutputStreamAdapter(stream.stream)
  }

  override fun openFileURI(fileName: String): String {
    val safFile = createStreamCached(fileName)
    return safFile.uri
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

  @Throws(IOException::class)
  override fun deleteFile(uriString: String) {
    val ctx = appContext ?: throw IOException("DeleteFile: not initialized")

    val uri = Uri.parse(uriString)
    val doc =
        DocumentFile.fromSingleUri(ctx, uri)
            ?: throw IOException("DeleteFile: cannot resolve URI $uriString")

    if (!doc.delete()) {
      throw IOException("DeleteFile: delete() returned false for $uriString")
    }
  }

  override fun treeURI(): String = savedUri ?: throw IllegalStateException("not initialized")

  fun generateNewFilename(filename: String): String {
    val dotIndex = filename.lastIndexOf('.')
    val baseName = if (dotIndex != -1) filename.substring(0, dotIndex) else filename
    val extension = if (dotIndex != -1) filename.substring(dotIndex) else ""

    val uuid = UUID.randomUUID()
    return "$baseName-$uuid$extension"
  }

  fun listPartialFiles(suffix: String): Array<String> {
    val context = appContext ?: return emptyArray()
    val rootUri = savedUri ?: return emptyArray()
    val dir = DocumentFile.fromTreeUri(context, Uri.parse(rootUri)) ?: return emptyArray()

    return dir.listFiles()
        .filter { it.name?.endsWith(suffix) == true }
        .mapNotNull { it.name }
        .toTypedArray()
  }

  override fun listPartialFilesJSON(suffix: String): String {
    return listPartialFiles(suffix)
        .joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")
  }

  override fun openPartialFileReader(name: String): libtailscale.InputStream? {
    val context = appContext ?: return null
    val rootUri = savedUri ?: return null
    val dir = DocumentFile.fromTreeUri(context, Uri.parse(rootUri)) ?: return null

    // We know `name` includes the suffix (e.g. ".<id>.partial"), but the actual
    // file in SAF might include extra bits, so let's just match by that suffix.
    // You could also match exactly `endsWith(name)` if the filenames line up
    val suffix = name.substringAfterLast('.', ".$name") // or hard-code ".partial"

    val file =
        dir.listFiles().firstOrNull {
          val fname = it.name ?: return@firstOrNull false
          // call the String overload explicitly:
          fname.endsWith(suffix, /*ignoreCase=*/ false)
        }
            ?: run {
              TSLog.d("ShareFileHelper", "no file ending with $suffix in SAF directory")
              return null
            }

    TSLog.d("ShareFileHelper", "found SAF file ${file.name}, opening")
    val inStream =
        context.contentResolver.openInputStream(file.uri)
            ?: run {
              TSLog.d("ShareFileHelper", "openInputStream returned null for ${file.uri}")
              return null
            }
    return InputStreamAdapter(inStream)
  }
}
