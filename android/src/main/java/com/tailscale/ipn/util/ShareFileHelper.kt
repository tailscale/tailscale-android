// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.util
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.tailscale.ipn.TaildropDirectoryStore
import com.tailscale.ipn.ui.util.InputStreamAdapter
import com.tailscale.ipn.ui.util.OutputStreamAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

  val taildropPrompt = MutableSharedFlow<Unit>(replay = 1)

  fun observeTaildropPrompt(): Flow<Unit> = taildropPrompt

  @Volatile private var directoryReady: CompletableDeferred<Unit>? = null

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
  @Throws(IOException::class)
  private fun openWriterFD(fileName: String, offset: Long): Pair<String, SeekableOutputStream> {
    val ctx = appContext ?: throw IOException("App context not initialized")
    val dirUri = savedUri ?: throw IOException("No directory URI")
    val dir =
        DocumentFile.fromTreeUri(ctx, Uri.parse(dirUri))
            ?: throw IOException("Invalid tree URI: $dirUri")
    val file =
        dir.findFile(fileName)
            ?: dir.createFile("application/octet-stream", fileName)
            ?: throw IOException("Failed to create file: $fileName")
    val pfd =
        ctx.contentResolver.openFileDescriptor(file.uri, "rw")
            ?: throw IOException("Failed to open file descriptor for ${file.uri}")
    val fos = FileOutputStream(pfd.fileDescriptor)
    if (offset != 0L) fos.channel.position(offset) else fos.channel.truncate(0)
    return file.uri.toString() to SeekableOutputStream(fos, pfd)
  }
  private val currentUri = ConcurrentHashMap<String, String>()

  @Throws(IOException::class)
  override fun openFileWriter(fileName: String, offset: Long): libtailscale.OutputStream {
    runBlocking { waitUntilTaildropDirReady() }
    val (uri, stream) = openWriterFD(fileName, offset)
    if (stream == null) {
      throw IOException("Failed to open file writer for $fileName")
    }
    currentUri[fileName] = uri
    return OutputStreamAdapter(stream)
  }

  @Throws(IOException::class)
  override fun getFileURI(fileName: String): String {
    runBlocking { waitUntilTaildropDirReady() }
    currentUri[fileName]?.let {
      return it
    }
    val ctx = appContext ?: throw IOException("App context not initialized")
    val dirStr = savedUri ?: throw IOException("No saved directory URI")
    val dir =
        DocumentFile.fromTreeUri(ctx, Uri.parse(dirStr))
            ?: throw IOException("Invalid tree URI: $dirStr")
    val file = dir.findFile(fileName) ?: throw IOException("File not found: $fileName")
    val uri = file.uri.toString()
    currentUri[fileName] = uri
    return uri
  }
  @Throws(IOException::class)
  override fun renameFile(oldPath: String, targetName: String): String {
    val ctx = appContext ?: throw IOException("not initialized")
    val dirUri = savedUri ?: throw IOException("directory not set")
    val srcUri = Uri.parse(oldPath)
    val dir =
        DocumentFile.fromTreeUri(ctx, Uri.parse(dirUri))
            ?: throw IOException("cannot open dir $dirUri")

    var finalName = targetName
    dir.findFile(finalName)?.let { existing ->
      if (lengthOfUri(ctx, existing.uri) == 0L) {
        existing.delete()
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
    } catch (e: Exception) {
      TSLog.w(
          "renameFile", "renameDocument fallback triggered for $srcUri -> $finalName: ${e.message}")
    }

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
  override fun deleteFile(uri: String) {
    runBlocking { waitUntilTaildropDirReady() }
    val ctx = appContext ?: throw IOException("DeleteFile: not initialized")
    val uri = Uri.parse(uri)
    val doc =
        DocumentFile.fromSingleUri(ctx, uri)
            ?: throw IOException("DeleteFile: cannot resolve URI $uri")
    if (!doc.delete()) {
      throw IOException("DeleteFile: delete() returned false for $uri")
    }
  }
  @Throws(IOException::class)
  override fun getFileInfo(fileName: String): String {
    val context = appContext ?: throw IOException("app context not initialized")
    val dirUri = savedUri ?: throw IOException("SAF URI not initialized")
    val dir =
        DocumentFile.fromTreeUri(context, Uri.parse(dirUri))
            ?: throw IOException("could not resolve SAF root")
    val file =
        dir.findFile(fileName) ?: throw IOException("file \"$fileName\" not found in SAF directory")
    val name = file.name ?: throw IOException("file name missing for $fileName")
    val size = file.length()
    val modTime = file.lastModified()
    return """{"name":${JSONObject.quote(name)},"size":$size,"modTime":$modTime}"""
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
  @Throws(IOException::class)
  override fun listFilesJSON(suffix: String): String {
    val list = listPartialFiles(suffix)
    if (list.isEmpty()) {
      throw IOException("no files found matching suffix \"$suffix\"")
    }
    return list.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")
  }
  @Throws(IOException::class)
  override fun openFileReader(name: String): libtailscale.InputStream {
    val context = appContext ?: throw IOException("app context not initialized")
    val rootUri = savedUri ?: throw IOException("SAF URI not initialized")
    val dir =
        DocumentFile.fromTreeUri(context, Uri.parse(rootUri))
            ?: throw IOException("could not open SAF root")
    val suffix = name.substringAfterLast('.', ".$name")
    val file =
        dir.listFiles().firstOrNull {
          val fname = it.name ?: return@firstOrNull false
          fname.endsWith(suffix, ignoreCase = false)
        } ?: throw IOException("no file ending with \"$suffix\" in SAF directory")
    val inStream =
        context.contentResolver.openInputStream(file.uri)
            ?: throw IOException("openInputStream returned null for ${file.uri}")
    return InputStreamAdapter(inStream)
  }

  fun setUri(uri: String) {
    savedUri = uri
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
        } finally {
          fos.close()
          pfd.close()
        }
      }
    }
    override fun flush() = fos.flush()
  }
}