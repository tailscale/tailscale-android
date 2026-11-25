// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.integrationprobe

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

class FilesProvider : ContentProvider() {
  override fun onCreate(): Boolean = true

  private fun file(uri: Uri): File =
      File(requireNotNull(context).filesDir, requireNotNull(uri.lastPathSegment))

  override fun query(
      uri: Uri,
      projection: Array<String>?,
      selection: String?,
      selectionArgs: Array<String>?,
      sortOrder: String?
  ): Cursor {
    val f = file(uri)
    return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
      addRow(arrayOf<Any?>(f.name, f.length()))
    }
  }

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
      ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

  override fun getType(uri: Uri): String = "application/octet-stream"

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

  override fun update(
      uri: Uri,
      values: ContentValues?,
      selection: String?,
      selectionArgs: Array<String>?
  ): Int = 0
}
