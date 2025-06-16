// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.util

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.tailscale.ipn.ui.util.InputStreamAdapter
import com.tailscale.ipn.ui.util.OutputStreamAdapter
import libtailscale.Libtailscale
import org.json.JSONObject
import java.io.FileOutputStream
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

  // A helper function that opens or creates a SafStream for a given file.
  private fun openSafFileOutputStream(fileName: String): Pair<String, OutputStream?> {
    val context = appContext ?: return "" to null
    val dirUri = savedUri ?: return "" to null
    val dir = DocumentFile.fromTreeUri(context, Uri.parse(dirUri)) ?: return "" to null

    val file =
        dir.findFile(fileName)
            ?: dir.createFile("application/octet-stream", fileName)
            ?: return "" to null

    val os = context.contentResolver.openOutputStream(file.uri, "rw")
    return file.uri.toString() to os
  }

  private fun openWriterFD(fileName: String, offset: Long): Pair<String, SeekableOutputStream?> {

    val ctx = appContext ?: return "" to null
    val dirUri = savedUri ?: return "" to null
    val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(dirUri)) ?: return "" to null

    // Reuse existing doc if it exists
    val file =
        dir.findFile(fileName)
            ?: dir.createFile("application/octet-stream", fileName)
            ?: return "" to null

    // Always get a ParcelFileDescriptor so we can sync
    val pfd = ctx.contentResolver.openFileDescriptor(file.uri, "rw") ?: return "" to null
    val fos = FileOutputStream(pfd.fileDescriptor)

    if (offset != 0L) fos.channel.position(offset) else fos.channel.truncate(0)

    return file.uri.toString() to SeekableOutputStream(fos, pfd)
  }

  private val currentUri = ConcurrentHashMap<String, String>()

  override fun openFileWriter(fileName: String): libtailscale.OutputStream {
    val (uri, stream) = openWriterFD(fileName, 0)
    currentUri[fileName] = uri // 🠚 cache the exact doc we opened
    return OutputStreamAdapter(stream ?: OutputStream.nullOutputStream())
  }

  override fun openFileWriterAt(fileName: String, offset: Long): libtailscale.OutputStream {
    val (uri, stream) = openWriterFD(fileName, offset)
    currentUri[fileName] = uri
    return OutputStreamAdapter(stream ?: OutputStream.nullOutputStream())
  }

  override fun openFileURI(fileName: String): String {
    currentUri[fileName]?.let {
      return it
    }
    val ctx = appContext ?: return ""
    val dirStr = savedUri ?: return ""
    val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(dirStr)) ?: return ""

    val file = dir.findFile(fileName) ?: return ""
    val uri = file.uri.toString()

    currentUri[fileName] = uri
    return uri
  }

  override fun renamePartialFile(
      partialUri: String,
      targetDirUri: String,
      targetName: String
  ): String {
    val ctx = appContext ?: throw IOException("not initialized")
    val srcUri = Uri.parse(partialUri)
    val dir =
        DocumentFile.fromTreeUri(ctx, Uri.parse(targetDirUri))
            ?: throw IOException("cannot open dir $targetDirUri")

    var finalName = targetName
    dir.findFile(finalName)?.let { existing ->
      if (lengthOfUri(ctx, existing.uri) == 0L) {
        existing.delete() // remove stale 0‑byte file
      } else {
        finalName = generateNewFilename(finalName)
      }
    }

    try {
      DocumentsContract.renameDocument(ctx.contentResolver, srcUri, finalName)?.also { newUri ->
        runCatching { ctx.contentResolver.delete(srcUri, null, null) }
        cleanupPartials(dir, targetName)
        return newUri.toString()
      }
    } catch (_: Exception) {
      // rename not supported; fall through to copy‑delete
    }

    // fallback - copy contents then delete source
    val dest =
        dir.createFile("application/octet-stream", finalName)
            ?: throw IOException("createFile failed for $finalName")

    ctx.contentResolver.openInputStream(srcUri).use { inp ->
      ctx.contentResolver.openOutputStream(dest.uri, "w").use { out ->
        if (inp == null || out == null) {
          dest.delete()
          throw IOException("Unable to open output stream for URI: ${dest.uri}")
        }
        inp.copyTo(out)
      }
    }
    // delete the original .partial
    ctx.contentResolver.delete(srcUri, null, null)
    cleanupPartials(dir, targetName)
    return dest.uri.toString()
  }

  private fun lengthOfUri(ctx: Context, uri: Uri): Long =
      ctx.contentResolver.openAssetFileDescriptor(uri, "r").use { it?.length ?: -1 }

  // delete any stray “.partial” files for this base name
  private fun cleanupPartials(dir: DocumentFile, base: String) {
    for (child in dir.listFiles()) {
      val n = child.name ?: continue
      if (n.endsWith(".partial") && n.contains(base, ignoreCase = false)) {
        child.delete()
      }
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

  override fun getFileInfo(fileName: String): String {
    val context = appContext ?: return ""
    val dirUri = savedUri ?: return ""
    val dir = DocumentFile.fromTreeUri(context, Uri.parse(dirUri)) ?: return ""

    val file = dir.findFile(fileName) ?: return ""

    val name = file.name ?: return ""
    val size = file.length()
    val modTime = file.lastModified() // milliseconds since epoch

    return """{"name":${jsonEscape(name)},"size":$size,"modTime":$modTime}"""
  }

  private fun jsonEscape(s: String): String {
    return JSONObject.quote(s)
  }

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

private class SeekableOutputStream(
    private val fos: FileOutputStream,
    private val pfd: ParcelFileDescriptor
) : OutputStream() {

  private var closed = false

  override fun write(b: Int) = fos.write(b)

  override fun write(b: ByteArray) = fos.write(b)

  override fun write(b: ByteArray, off: Int, len: Int) {
    fos.write(b, off, len)
  }

  override fun close() {
    if (!closed) {
      closed = true
      try {
        fos.flush()
        fos.fd.sync() // blocks until data + metadata are durable
        val size = fos.channel.size()
      } finally {
        fos.close()
        pfd.close()
      }
    }
  }

  override fun flush() = fos.flush()
}
