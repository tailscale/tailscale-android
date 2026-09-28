// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class ShareFileHelperTest {
  private val internalDir = "/data/user/0/com.tailscale.ipn/files"
  private val pickedDir = "content://com.android.externalstorage.documents/tree/primary%3ADownload"

  // A transfer that arrives before a folder is picked blocks until setUri. It must resume against
  // the picked folder: setUri has to publish the root before waking it, or the transfer can run
  // first and write to the filesDir fallback.
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test(timeout = 10_000)
  fun transferWaitingForDirectoryUsesPickedDirectory() {
    val file = File.createTempFile("taildrop", ".partial").apply { deleteOnExit() }
    val fileUri = fakeUri("$pickedDir/document/a.partial")
    val fd = FileOutputStream(file).fd
    val pfd = mock<ParcelFileDescriptor> { on { fileDescriptor } doReturn fd }
    val resolver = mock<ContentResolver> { on { openFileDescriptor(fileUri, "rw") } doReturn pfd }
    val ctx = mock<Context> { on { contentResolver } doReturn resolver }
    val partial = mock<DocumentFile> { on { uri } doReturn fileUri }
    val dir =
        mock<DocumentFile> {
          on { exists() } doReturn true
          on { canWrite() } doReturn true
          on { createFile(any(), any()) } doReturn partial
        }

    val logWrapper = TSLog.libtailscaleWrapper
    TSLog.libtailscaleWrapper = mock()

    // State after App.startLibtailscale on a fresh install: no SAF dir, root is filesDir.
    setStatic(ShareFileHelper::class.java, "appContext", ctx)
    setStatic(ShareFileHelper::class.java, "savedUri", internalDir)
    setStatic(ShareFileHelper::class.java, "scope", CoroutineScope(Dispatchers.Default))
    ShareFileHelper.taildropPrompt.resetReplayCache()

    // Static mocks are thread-local, so the transfer runs here and the picker on another thread.
    val logs = mockStatic(Log::class.java)
    val uris = mockStatic(Uri::class.java)
    val docs = mockStatic(DocumentFile::class.java)
    try {
      uris.`when`<Uri> { Uri.parse(any()) }.thenAnswer { fakeUri(it.getArgument(0)) }
      // Mirrors DocumentsContract.getTreeDocumentId, which rejects non-tree URIs.
      docs
          .`when`<DocumentFile> { DocumentFile.fromTreeUri(any(), any()) }
          .thenAnswer {
            val u = it.getArgument<Uri>(1).toString()
            if (u == pickedDir) dir else throw IllegalArgumentException("Invalid URI: $u")
          }

      var rootAtWake: Any? = null
      val picker = thread {
        runBlocking { withTimeout(5_000) { ShareFileHelper.observeTaildropPrompt().first() } }
        // Completion handlers run synchronously inside complete(), so this sees the root exactly
        // when the transfer is released. Waking first loses the race on a busy scheduler.
        (getStatic(ShareFileHelper::class.java, "directoryReady") as Job).invokeOnCompletion {
          rootAtWake = getStatic(ShareFileHelper::class.java, "savedUri")
        }
        // What MainActivity's directoryPickerLauncher callback does once the user picks a folder.
        ShareFileHelper.setUri(pickedDir)
      }

      val result = runCatching { ShareFileHelper.openFileWriter("a.partial", 0).close() }
      picker.join()
      assertEquals(pickedDir, rootAtWake)
      assertTrue("openFileWriter: ${result.exceptionOrNull()}", result.isSuccess)
    } finally {
      docs.close()
      uris.close()
      logs.close()
      TSLog.libtailscaleWrapper = logWrapper
    }
  }

  private fun fakeUri(s: String): Uri = mock {
    on { scheme } doReturn s.substringBefore("://", "")
    on { toString() } doReturn s
  }

  private fun getStatic(owner: Class<*>, name: String): Any? =
      owner.getDeclaredField(name).apply { isAccessible = true }.get(null)

  private fun setStatic(owner: Class<*>, name: String, value: Any?) {
    owner.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
  }
}
