// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.view.ActivityIndicator
import com.tailscale.ipn.ui.view.CheckedIndicator
import com.tailscale.ipn.ui.view.ErrorDialogType
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.util.TaildropUsageTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class TaildropViewModelFactory(
    private val requestedTransfers: StateFlow<List<Ipn.OutgoingFile>>,
    private val applicationScope: CoroutineScope
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return TaildropViewModel(requestedTransfers, applicationScope) as T
  }
}

class TaildropViewModel(
    private val requestedTransfers: StateFlow<List<Ipn.OutgoingFile>>,
    private val applicationScope: CoroutineScope
) : IpnViewModel() {

  // Represents the state of a file transfer
  enum class TransferState {
    SENDING,
    SENT,
    FAILED
  }

  // The overall VPN state
  val state = Notifier.state

  // Set of OutgoingFile.IDs that we're currently transferring.
  private val currentTransferIDs: StateFlow<Set<String>> = MutableStateFlow(emptySet())
  // Flow of Ipn.OutgoingFiles with updated statuses for every entry in transferWithStatuses.
  private val transfers: StateFlow<List<Ipn.OutgoingFile>> = MutableStateFlow(emptyList())

  // The total size of all pending files.
  val totalSize: Long
    get() = requestedTransfers.value.sumOf { it.DeclaredSize }

  // Recently shared-to devices (capped at 3) and everything else.
  val recentPeers: StateFlow<List<Tailcfg.Node>> = MutableStateFlow(emptyList())
  val otherPeers: StateFlow<List<Tailcfg.Node>> = MutableStateFlow(emptyList())

  // Non null if there's an error to be rendered.
  val showDialog: StateFlow<ErrorDialogType?> = MutableStateFlow(null)

  private var refreshJob: Job? = null
  private val refreshIntervalMs = 5_000L

  init {
    viewModelScope.launch {
      Notifier.state.collect {
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
      Notifier.outgoingFiles
          .combine(currentTransferIDs) { outgoingFiles, ongoingIDs ->
            Pair(outgoingFiles, ongoingIDs)
          }
          .collect { (outgoingFiles, ongoingIDs) ->
            outgoingFiles?.let {
              transfers.set(outgoingFiles.filter { ongoingIDs.contains(it.ID) })
            } ?: run { transfers.set(emptyList()) }
          }
    }

    viewModelScope.launch {
      requestedTransfers.collect {
        // New share intent — drop tracking of any prior transfer IDs.
        currentTransferIDs.set(emptySet())
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
    refreshJob =
        viewModelScope.launch {
          while (true) {
            loadTargets()
            delay(refreshIntervalMs)
          }
        }
  }

  // Loads valid fileTargets from localAPI and splits into recent vs other.
  private fun loadTargets() {
    Client(viewModelScope).fileTargets { result ->
      result
          .onSuccess { targets ->
            val ctx = App.get()
            val userID = Notifier.netmap.value?.SelfNode?.User ?: 0L
            val all = targets.map { it.Node }
            val (recent, other) = TaildropUsageTracker.partitionByRecency(ctx, userID, all)
            val onlineFirst =
                other.sortedWith(
                    compareByDescending<Tailcfg.Node> { it.Online ?: false }
                        .thenBy { (it.ComputedName ?: it.Name).lowercase() })
            recentPeers.set(recent)
            otherPeers.set(onlineFirst)
          }
          .onFailure { TSLog.e(TAG, "Error loading targets: ${it.message}") }
    }
  }

  // Creates the trailing status view for the peer list item depending on the state of
  // any requested transfers.
  @Composable
  fun TrailingContentForPeer(peerId: String) {
    // Check our outgoing files for the peer and determine the state of the transfer.
    val transfers = this.transfers.collectAsState().value.filter { it.PeerID == peerId }
    val status: TransferState = transferState(transfers) ?: return

    // Still no status? Nothing to render for this peer

    Column(modifier = Modifier.fillMaxHeight()) {
      when (status) {
        TransferState.SENDING -> {
          val progress = progress(transfers)
          Text(
              stringResource(id = R.string.taildrop_sending),
              style = MaterialTheme.typography.bodyMedium)
          ActivityIndicator(progress, 60)
        }
        TransferState.SENT -> CheckedIndicator()
        TransferState.FAILED -> Text(stringResource(id = R.string.taildrop_share_failed_short))
      }
    }
  }

  // Commences the file transfer to the specified node iff
  fun share(context: Context, node: Tailcfg.Node) {
    if (node.Online != true) {
      showDialog.set(ErrorDialogType.SHARE_DEVICE_NOT_CONNECTED)
      return
    }

    // Gate on an actually-in-flight transfer rather than a one-shot selection
    // set, since requestedTransfers may not re-emit on a new intent when the
    // share content hashes identically.
    if (transfers.value.any { it.PeerID == node.StableID && !it.Finished }) return

    val preparedTransfers = requestedTransfers.value.map { it.prepare(node.StableID) }
    currentTransferIDs.set(currentTransferIDs.value + preparedTransfers.map { it.ID })

    Client(applicationScope).putTaildropFiles(context, node.StableID, preparedTransfers) {
      if (it.isFailure) {
        showDialog.set(ErrorDialogType.SHARE_FAILED)
      } else {
        val userID = Notifier.netmap.value?.SelfNode?.User ?: 0L
        TaildropUsageTracker.updateLastUsed(context, userID, node.StableID)
      }
    }
  }
}
