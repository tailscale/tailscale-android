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
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.view.ActivityIndicator
import com.tailscale.ipn.ui.view.CheckedIndicator
import com.tailscale.ipn.ui.view.ErrorDialogType
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
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

  // Set of all nodes for which we've requested a file transfer. This is used to prevent us from
  // request a transfer to the same peer twice.
  private val selectedPeers: StateFlow<Set<StableNodeID>> = MutableStateFlow(emptySet())
  // Set of OutgoingFile.IDs that we're currently transferring.
  private val currentTransferIDs: StateFlow<Set<String>> = MutableStateFlow(emptySet())
  // Flow of Ipn.OutgoingFiles with updated statuses for every entry in transferWithStatuses.
  private val transfers: StateFlow<List<Ipn.OutgoingFile>> = MutableStateFlow(emptyList())

  // The total size of all pending files.
  val totalSize: Long
    get() = requestedTransfers.value.sumOf { it.DeclaredSize }

  // The list of peers that we can share with.  This includes only the nodes belonging to the user
  // and excludes the current node.  Sorted by online devices first, and offline second,
  // alphabetically.
  val myPeers: StateFlow<List<Tailcfg.Node>> = MutableStateFlow(emptyList())

  // Non null if there's an error to be rendered.
  val showDialog: StateFlow<ErrorDialogType?> = MutableStateFlow(null)

  init {
    viewModelScope.launch {
      Notifier.state.collect {
        if (it == Ipn.State.Running) {
          loadTargets()
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
        // This means that we're processing a new share intent, clear current state
        selectedPeers.set(emptySet())
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

  // Loads all of the valid fileTargets from localAPI
  private fun loadTargets() {
    Client(viewModelScope).fileTargets { result ->
      result
          .onSuccess { it ->
            val allSharablePeers = it.map { it.Node }
            val onlinePeers = allSharablePeers.filter { it.Online ?: false }.sortedBy { it.Name }
            val offlinePeers =
                allSharablePeers.filter { !(it.Online ?: false) }.sortedBy { it.Name }
            myPeers.set(onlinePeers + offlinePeers)
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

    if (selectedPeers.value.contains(node.StableID)) {
      // We've already selected this peer, ignore
      return
    }
    selectedPeers.set(selectedPeers.value + node.StableID)

    val preparedTransfers = requestedTransfers.value.map { it.prepare(node.StableID) }
    currentTransferIDs.set(currentTransferIDs.value + preparedTransfers.map { it.ID })

    Client(applicationScope).putTaildropFiles(context, node.StableID, preparedTransfers) {
      // This is an early API failure and will not get communicated back up to us via
      // outgoing files - things never made it that far.
      if (it.isFailure) {
        selectedPeers.set(selectedPeers.value - node.StableID)
        showDialog.set(ErrorDialogType.SHARE_FAILED)
      }
    }
  }
}
