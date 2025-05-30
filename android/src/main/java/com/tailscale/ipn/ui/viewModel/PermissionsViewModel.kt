// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.tailscale.ipn.TaildropDirectoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import libtailscale.Libtailscale

class PermissionsViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

  private val _currentDir =
      MutableStateFlow<String?>(TaildropDirectoryStore.loadSavedDir().toString())
  val currentDir: StateFlow<String?> = _currentDir

  fun onDirectoryPicked(uri: Uri?, context: Context) {
    if (uri == null) return

    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val cr = context.contentResolver

    // Revoke previous grant so you don’t leak one
    _currentDir.value?.let { old ->
      runCatching { cr.releasePersistableUriPermission(Uri.parse(old), flags) }
    }

    cr.takePersistableUriPermission(uri, flags) // may throw SecurityException
    Libtailscale.setDirectFileRoot(uri.toString())
    TaildropDirectoryStore.saveFileDirectory(uri)

    _currentDir.value = uri.toString()
  }
}
