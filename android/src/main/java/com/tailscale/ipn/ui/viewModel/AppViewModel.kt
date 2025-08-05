// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import android.net.Uri
import android.net.VpnService
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.util.ShareFileHelper
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppViewModelFactory(val application: Application, private val taildropPrompt: Flow<Unit>) :
    ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
      return AppViewModel(application, taildropPrompt) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}

// Application context-aware ViewModel used to track app-wide VPN and Taildrop state.
// This must be application-scoped because Tailscale may be enabled, disabled, or used for
// file transfers (Taildrop) outside the activity lifecycle.
//
// Responsibilities:
// - Track VPN preparation state (e.g., whether permission has been granted) and activity state
// - Monitor incoming Taildrop file transfers
// - Coordinate prompts for Taildrop directory selection if not yet configured
class AppViewModel(application: Application, private val taildropPrompt: Flow<Unit>) :
    AndroidViewModel(application) {
  // Whether the VPN is prepared. This is set to true if the VPN application is already prepared, or
  // if the user has previously consented to the VPN application. This is used to determine whether
  // a VPN permission launcher needs to be shown.
  val _vpnPrepared = MutableStateFlow(false)
  val vpnPrepared: StateFlow<Boolean> = _vpnPrepared
  // Whether a VPN interface has been established. This is set by net.updateTUN upon
  // VpnServiceBuilder.establish, and consumed by UI to reflect VPN state.
  val _vpnActive = MutableStateFlow(false)
  val vpnActive: StateFlow<Boolean> = _vpnActive
  // Select Taildrop directory
  var directoryPickerLauncher: ActivityResultLauncher<Uri?>? = null
  private val _showDirectoryPickerInterstitial = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val showDirectoryPickerInterstitial: SharedFlow<Unit> = _showDirectoryPickerInterstitial
  val TAG = "AppViewModel"

  init {
    observeIncomingTaildrop()
    prepareVpn()
  }

  private fun observeIncomingTaildrop() {
    viewModelScope.launch {
      taildropPrompt.collect {
        TSLog.d(TAG, "Taildrop event received, checking directory")
        checkIfTaildropDirectorySelected()
      }
    }
  }

  private fun prepareVpn() {
    // Check if the user has granted permission yet.
    if (!vpnPrepared.value) {
      val vpnIntent = VpnService.prepare(getApplication())
      if (vpnIntent != null) {
        setVpnPrepared(false)
        Log.d(TAG, "VpnService.prepare returned non-null intent")
      } else {
        setVpnPrepared(true)
        Log.d(TAG, "VpnService.prepare returned null intent, VPN is already prepared")
      }
    }
  }

  fun checkIfTaildropDirectorySelected() {
    val app = App.get()
    val storedUri = app.getStoredDirectoryUri()
    if (ShareFileHelper.hasValidTaildropDir()) {
      return
    }

    val documentFile = storedUri?.let { DocumentFile.fromTreeUri(app, it) }
    if (documentFile == null || !documentFile.exists() || !documentFile.canWrite()) {
      TSLog.d(
          "MainViewModel",
          "Stored directory URI is invalid or inaccessible; launching directory picker.")
      viewModelScope.launch { _showDirectoryPickerInterstitial.tryEmit(Unit) }
    } else {
      TSLog.d("MainViewModel", "Using stored directory URI: $storedUri")
    }
  }

  fun setVpnActive(isActive: Boolean) {
    _vpnActive.value = isActive
  }

  fun setVpnPrepared(isPrepared: Boolean) {
    _vpnPrepared.value = isPrepared
  }
}