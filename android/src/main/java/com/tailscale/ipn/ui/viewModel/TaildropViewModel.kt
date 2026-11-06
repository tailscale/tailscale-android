// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.dataSource.TaildropDataSource
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.view.ErrorDialogType
import com.tailscale.ipn.util.TSLog
import kotlin.collections.filter
import kotlin.collections.orEmpty
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class TaildropViewModelFactory(
    private val dataSource: TaildropDataSource,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(TaildropViewModel::class.java)) {
      return TaildropViewModel(dataSource) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}

class TaildropViewModel(
    private val dataSource: TaildropDataSource,
) : ViewModel() {
  private val TAG = "TaildropViewModel"
  private val requestedTransfers = dataSource.requestedTransfers

  // Represents the state of a file transfer
  enum class TransferState {
    SENDING,
    SENT,
    FAILED,
  }

  // The overall VPN state
  val state = dataSource.state

  // Set of OutgoingFile.IDs that we're currently transferring.
  private val currentTransferIDs = MutableStateFlow<Set<String>>(emptySet())
  // Flow of Ipn.OutgoingFiles with updated statuses for every entry in transferWithStatuses.
  private val _transfers = MutableStateFlow<List<Ipn.OutgoingFile>>(emptyList())
  val transfers: StateFlow<List<Ipn.OutgoingFile>> = _transfers

  // The total size of all pending files.
  val totalSize: Long
    get() = requestedTransfers.value.sumOf { it.DeclaredSize }

  // Recently shared-to devices (capped at 3) and everything else.
  private val _recentPeers = MutableStateFlow<List<Tailcfg.Node>>(emptyList())
  val recentPeers: StateFlow<List<Tailcfg.Node>> = _recentPeers.asStateFlow()
  private val _otherPeers = MutableStateFlow<List<Tailcfg.Node>>(emptyList())
  val otherPeers: StateFlow<List<Tailcfg.Node>> = _otherPeers.asStateFlow()

  // Non-null if there's an error to be rendered.
  private val _showDialog = MutableStateFlow<ErrorDialogType?>(null)
  val showDialog: StateFlow<ErrorDialogType?> = _showDialog.asStateFlow()

  private var refreshJob: Job? = null
  private val refreshIntervalMs = 5_000L

  init {
    viewModelScope.launch {
      dataSource.state.collect {
        if (it == Ipn.State.Running) {
          startPeriodicTargetRefresh()
        } else {
          refreshJob?.cancel()
          refreshJob = null
        }
      }
    }

    viewModelScope.launch {
      // Map the outgoing files by their PeerId since we need to display them for each peer
      // We only need to track files which are pending send, everything else is irrelevant.
      dataSource.outgoingFiles
          .combine(currentTransferIDs) { outgoingFiles, ongoingIds ->
            outgoingFiles.orEmpty().filter { it.ID in ongoingIds }
          }
          .collect { _transfers.value = it }
    }

    viewModelScope.launch {
      requestedTransfers.collect {
        // New share intent — drop tracking of any prior transfer IDs.
        currentTransferIDs.value = emptySet()
      }
    }
  }

  // Calculates the overall progress for a set of outgoing files
  private fun progress(transfers: List<Ipn.OutgoingFile>): Double {
    val total = transfers.sumOf { it.DeclaredSize }.toDouble()
    val sent = transfers.sumOf { it.Sent }.toDouble()
    if (total < 0.1) return 0.0
    return (sent / total)
  }

  // Calculates the overall state of a set of file transfers.
  // peerId: The peer ID to check for transfers.
  // transfers: The list of outgoing file transfers for the peer.
  private fun transferState(transfers: List<Ipn.OutgoingFile>): TransferState? {
    // No transfers? Nothing state
    if (transfers.isEmpty()) return null

    return if (transfers.all { it.Finished }) {
      // Everything done?  SENT if all succeeded, FAILED if any failed.
      if (transfers.any { !it.Succeeded }) TransferState.FAILED else TransferState.SENT
    } else {
      // Not complete, we're still sending
      TransferState.SENDING
    }
  }

  // Re-polls fileTargets so the device list updates as peers come online.
  private fun startPeriodicTargetRefresh() {
    refreshJob?.cancel()
    refreshJob = viewModelScope.launch {
      while (true) {
        loadTargets()
        delay(refreshIntervalMs.milliseconds)
      }
    }
  }

  // Loads valid fileTargets from localAPI and splits into recent vs other.
  private fun loadTargets() {
    dataSource.loadPeers(viewModelScope) { result ->
      result
          .onSuccess {
            _recentPeers.value = it.recent
            _otherPeers.value = it.other
          }
          .onFailure { TSLog.e(TAG, "Error loading targets: ${it.message}") }
    }
  }

  // Commences the file transfer to the specified node iff
  fun share(node: Tailcfg.Node) {
    if (node.Online != true) {
      _showDialog.value = ErrorDialogType.SHARE_DEVICE_NOT_CONNECTED
      return
    }

    // Gate on an actually-in-flight transfer rather than a one-shot selection
    // set, since requestedTransfers may not re-emit on a new intent when the
    // share content hashes identically.
    if (transfers.value.any { it.PeerID == node.StableID && !it.Finished }) return

    val preparedTransfers = requestedTransfers.value.map { it.prepare(node.StableID) }
    currentTransferIDs.value += preparedTransfers.map { it.ID }

    dataSource.sendFiles(node, preparedTransfers) {
      if (it.isFailure) {
        _showDialog.value = ErrorDialogType.SHARE_FAILED
      }
    }
  }

  fun dismissDialog() {
    _showDialog.value = null
  }

  fun startVPN() = dataSource.startVPN()

  data class PeerTransferStatus(
      val state: TransferState,
      val progress: Double,
  )

  fun transferStatusForPeer(files: List<Ipn.OutgoingFile>): PeerTransferStatus? {
    val status = transferState(files) ?: return null
    val progress = if (status == TransferState.SENDING) progress(files) else 0.0
    return PeerTransferStatus(status, progress)
  }
}
