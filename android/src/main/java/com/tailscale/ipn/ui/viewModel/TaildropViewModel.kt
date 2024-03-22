// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.content.Context
import android.util.Log
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
import com.tailscale.ipn.ui.model.FileTransfer
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.view.ActivityIndicator
import com.tailscale.ipn.ui.view.CheckedIndicator
import com.tailscale.ipn.ui.view.ErrorDialogType
import java.net.URLEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TaildropViewModelFactory(private val transfers: StateFlow<List<FileTransfer>>) :
    ViewModelProvider.Factory {
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return TaildropViewModel(transfers) as T
  }
}

class TaildropViewModel(val transfers: StateFlow<List<FileTransfer>>) : IpnViewModel() {

  // Represents the state of a file transfer
  enum class TransferState {
    SENDING,
    SENT,
    FAILED
  }

  // The overall VPN state
  val state = Notifier.state

  // Map of outgoing files to peer ID.  This is the full state of the outgoing files.
  private val outgoing: StateFlow<Map<String, List<Ipn.OutgoingFile>>> =
      MutableStateFlow(emptyMap())

  // List of any nodes that have a file transfer pending FOR THE CURRENT SESSION
  // This is used to filter outgoingFiles to ensure we only render the transfer state
  // for things that are currently displayed.
  private val pending: StateFlow<Set<String>> = MutableStateFlow(emptySet())

  // The total size of all pending files.
  var totalSize: Long = 0
    get() = transfers.value.sumOf { it.size }

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
      Notifier.outgoingFiles.collect { outgoingFiles ->
        val outgoingMap: MutableMap<String, List<Ipn.OutgoingFile>> = mutableMapOf()
        val currentFiles = transfers.value.map { URLEncoder.encode(it.filename, "utf-8") }

        outgoingFiles?.let { files ->
          files
              .filter { currentFiles.contains(it.Name) && pending.value.contains(it.PeerID) }
              .forEach {
                val list = outgoingMap.getOrDefault(it.PeerID, emptyList()).toMutableList()
                list += it
                outgoingMap[it.PeerID] = list
              }
          Log.d("TaildropViewModel", "Outgoing files: $outgoingMap")
          outgoing.set(outgoingMap)
        } ?: run { outgoing.set(emptyMap()) }
      }
    }

    // Whenever our files list changes, we need to reset the outgoings files map and
    // any pending requests.  The user has changed the files they're attempting to share.
    viewModelScope.launch {
      transfers.collect {
        pending.set(emptySet())
        outgoing.set(emptyMap())
      }
    }
  }

  // Calculates the overall progress for a set of outoing files
  private fun progress(transfers: List<Ipn.OutgoingFile>): Double {
    val total = transfers.sumOf { it.DeclaredSize }.toDouble()
    val sent = transfers.sumOf { it.Sent }.toDouble()
    if (total < 0.1) return 0.0
    return (sent / total)
  }

  // Calculates the overall state of a set of file transfers.
  // peerId: The peer ID to check for transfers.
  // transfers: The list of outgoing file transfers for the peer.
  private fun transferState(peerId: String, transfers: List<Ipn.OutgoingFile>): TransferState? {
    // No transfers? Nothing state
    if (transfers.isEmpty()) return null

    // We may have transfers from a prior session for files the user selected and for peers
    // in our list.. but we don't care about those.  We only care if the peerId is in teh pending
    // list.
    if (!pending.value.contains(peerId)) return null

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
          .onFailure { Log.e(TAG, "Error loading targets: ${it.message}") }
    }
  }

  // Creates the trailing status view for the peer list item depending on the state of
  // any requested transfers.
  @Composable
  fun TrailingContentForPeer(peerId: String) {
    val outgoing = outgoing.collectAsState().value
    val pending = pending.collectAsState().value

    // Check our outgoing files for the peer and determine the state of the transfer.
    val transfers = outgoing[peerId] ?: emptyList()
    var status = transferState(peerId, transfers)

    // Check if we have a pending transfer for this peer.  We may not have an outgoing file
    // yet, but we still want to show the sending state in the mean time.
    if (status == null && pending.contains(peerId)) {
      status = TransferState.SENDING
    }

    // Still no status? Nothing to render for this peer
    if (status == null) return

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

    // Ignore requests to resend a file (the backend will not overwrite anyway)
    outgoing.value[node.StableID]?.let {
      val status = transferState(node.StableID, it)
      if (status == TransferState.SENDING || status == TransferState.SENT) {
        return
      }
    }

    pending.set(pending.value + node.StableID)
    Client(viewModelScope).putTaildropFiles(context, node.StableID, transfers.value) {
      // This is an early API failure and will not get communicated back up to us via
      // outgoing files - things never made it that far.
      if (it.isFailure) {
        pending.set(pending.value - node.StableID)
        showDialog.set(ErrorDialogType.SHARE_FAILED)
      }
    }
  }
}
