// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.annotation.SuppressLint
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.components.ActivityIndicator
import com.tailscale.ipn.ui.components.CheckedIndicator
import com.tailscale.ipn.ui.dataSource.TaildropDataSource
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.Lists.SectionDivider
import com.tailscale.ipn.ui.viewModel.TaildropViewModel
import com.tailscale.ipn.ui.viewModel.TaildropViewModel.TransferState
import com.tailscale.ipn.ui.viewModel.TaildropViewModelFactory
import com.tailscale.ipn.util.InlineShare
import com.tailscale.ipn.util.TSLog
import kotlin.Boolean

@Composable
fun TaildropView(dataSource: TaildropDataSource) {
  val TAG = "TaildropView"
  val context = LocalContext.current

  val factory = remember(dataSource) { TaildropViewModelFactory(dataSource) }
  val taildropViewModel: TaildropViewModel = viewModel(factory = factory)

  val focusRequester = remember { FocusRequester() }
  val requestedTransfers by dataSource.requestedTransfers.collectAsStateWithLifecycle()
  val transfers by taildropViewModel.transfers.collectAsStateWithLifecycle()
  val showDialog by taildropViewModel.showDialog.collectAsStateWithLifecycle()
  val state by taildropViewModel.state.collectAsStateWithLifecycle()
  val recent by taildropViewModel.recentPeers.collectAsStateWithLifecycle()
  val other by taildropViewModel.otherPeers.collectAsStateWithLifecycle()

  // Automatically request focus when the composable is displayed
  LaunchedEffect(Unit) {
    try {
      focusRequester.requestFocus()
    } catch (e: Exception) {
      TSLog.w(TAG, "Focus request failed: ${e.message}")
    }
  }

  TaildropScreen(
      state =
          TaildropUIState(
              running = state == Ipn.State.Running,
              requestedTransfers = requestedTransfers,
              totalSize = taildropViewModel.totalSize,
              recent = recent,
              other = other,
              transfers = transfers,
          ),
      focusRequester = focusRequester,
      onShare = taildropViewModel::share,
      onToggle = taildropViewModel::startVPN,
      transferStatusForPeer = taildropViewModel::transferStatusForPeer,
      getType = context.contentResolver::getType,
  )

  showDialog?.let { ErrorDialog(type = it, action = { taildropViewModel.dismissDialog() }) }
}

// Creates the trailing status view for the peer list item depending on the state of
// any requested transfers.
@Composable
private fun TrailingContentForPeer(status: TaildropViewModel.PeerTransferStatus) {
  Column(modifier = Modifier.fillMaxHeight()) {
    when (status.state) {
      TransferState.SENDING -> {
        Text(
            stringResource(id = R.string.taildrop_sending),
            style = MaterialTheme.typography.bodyMedium,
        )
        ActivityIndicator(status.progress, 60)
      }
      TransferState.SENT -> CheckedIndicator()
      TransferState.FAILED -> Text(stringResource(id = R.string.taildrop_share_failed_short))
    }
  }
}

@Composable
private fun FileSharePeerList(
    recent: List<Tailcfg.Node>,
    other: List<Tailcfg.Node>,
    stateViewGenerator: @Composable (String) -> Unit,
    onShare: (Tailcfg.Node) -> Unit,
) {
  if (recent.isEmpty() && other.isEmpty()) {
    SectionDivider(stringResource(R.string.my_devices))
    Column(
        modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
          stringResource(R.string.no_devices_to_share_with),
          style = MaterialTheme.typography.titleMedium,
      )
    }
    return
  }

  LazyColumn {
    if (recent.isNotEmpty()) {
      item { SectionDivider(stringResource(R.string.taildrop_recently_used)) }
      items(recent) { PeerRow(it, stateViewGenerator, onShare) }
      item { SectionDivider(stringResource(R.string.taildrop_all_devices)) }
    } else {
      item { SectionDivider(stringResource(R.string.my_devices)) }
    }
    items(other) { PeerRow(it, stateViewGenerator, onShare) }
  }
}

@Composable
private fun PeerRow(
    peer: Tailcfg.Node,
    stateViewGenerator: @Composable (String) -> Unit,
    onShare: (Tailcfg.Node) -> Unit,
) {
  PeerView(
      peer = peer,
      onClick = { onShare(peer) },
      subtitle = { peer.Hostinfo.OS ?: "" },
      trailingContent = { stateViewGenerator(peer.StableID) },
  )
}

@Composable
private fun FileShareConnectView(onToggle: () -> Unit) {
  Column(
      modifier = Modifier.padding(horizontal = 16.dp).fillMaxHeight(),
      verticalArrangement = Arrangement.spacedBy(6.dp, alignment = Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
        stringResource(R.string.connect_to_your_tailnet_to_share_files),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(modifier = Modifier.size(1.dp))
    PrimaryActionButton(onClick = onToggle) {
      Text(
          text = stringResource(id = R.string.connect),
          fontSize = MaterialTheme.typography.titleMedium.fontSize,
      )
    }
  }
}

@Composable
private fun FileShareHeader(
    fileTransfers: List<Ipn.OutgoingFile>,
    totalSize: Long,
    getType: (Uri) -> String?,
) {
  Column(modifier = Modifier.padding(horizontal = 12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconForTransfer(fileTransfers, getType)
      Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        val share = fileTransfers.singleOrNull()?.inlineShare
        when {
          fileTransfers.isEmpty() ->
              Text(
                  stringResource(R.string.no_files_to_share),
                  style = MaterialTheme.typography.titleMedium,
              )
          share != null ->
              Text(
                  text = share.content,
                  style = MaterialTheme.typography.titleMedium,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
              )
          fileTransfers.size == 1 -> {
            Text(fileTransfers[0].Name, style = MaterialTheme.typography.titleMedium)
            FileSizeText(totalSize)
          }
          else -> {
            Text(
                stringResource(R.string.file_count, fileTransfers.size),
                style = MaterialTheme.typography.titleMedium,
            )
            FileSizeText(totalSize)
          }
        }
      }
    }
  }
}

@Composable
private fun FileSizeText(totalSize: Long) {
  Text(
      Formatter.formatFileSize(LocalContext.current, totalSize),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.secondary,
  )
}

@Composable
private fun IconForTransfer(
    transfers: List<Ipn.OutgoingFile>,
    getType: (Uri) -> String?,
) {
  // (jonathan) TODO: Thumbnails?
  when (transfers.size) {
    0 ->
        Icon(
            painter = painterResource(R.drawable.warning),
            contentDescription = "no files",
            modifier = Modifier.size(32.dp),
        )
    1 -> {
      val only = transfers[0]
      val share = only.inlineShare
      if (share != null) {
        val resource =
            if (share.kind == InlineShare.Kind.URL) R.drawable.link else R.drawable.single_file
        Icon(
            painter = painterResource(resource),
            contentDescription = share.kind.name.lowercase(),
            modifier = Modifier.size(40.dp),
        )
        return
      }
      // Show a thumbnail for single image shares.
      getType(only.uri)?.let {
        if (it.startsWith("image/")) {
          AsyncImage(
              model = only.uri,
              contentDescription = "one file",
              modifier = Modifier.size(40.dp),
          )
          return
        }

        Icon(
            painter = painterResource(R.drawable.single_file),
            contentDescription = "files",
            modifier = Modifier.size(40.dp),
        )
      }
    }
    else ->
        Icon(
            painter = painterResource(R.drawable.single_file),
            contentDescription = "files",
            modifier = Modifier.size(40.dp),
        )
  }
}

private data class TaildropUIState(
    val running: Boolean,
    val requestedTransfers: List<Ipn.OutgoingFile>,
    val totalSize: Long,
    val recent: List<Tailcfg.Node>,
    val other: List<Tailcfg.Node>,
    val transfers: List<Ipn.OutgoingFile>,
)

@Composable
private fun TaildropScreen(
    state: TaildropUIState,
    focusRequester: FocusRequester,
    onShare: (Tailcfg.Node) -> Unit,
    onToggle: () -> Unit,
    transferStatusForPeer: (files: List<Ipn.OutgoingFile>) -> TaildropViewModel.PeerTransferStatus?,
    getType: (Uri) -> String?,
) {
  Column(modifier = Modifier.focusRequester(focusRequester).focusable()) {
    FileShareHeader(
        fileTransfers = state.requestedTransfers,
        totalSize = state.totalSize,
        getType = getType,
    )

    if (state.running) {
      FileSharePeerList(
          recent = state.recent,
          other = state.other,
          stateViewGenerator = { peerId ->
            val peerFiles = state.transfers.filter { it.PeerID == peerId }
            val status = transferStatusForPeer(peerFiles)
            if (status != null) {
              TrailingContentForPeer(status = status)
            }
          },
          onShare = onShare,
      )
    } else {
      FileShareConnectView(onToggle = onToggle)
    }
  }
}

@SuppressLint("UseKtx")
@Preview(showSystemUi = true)
@Composable
private fun TaildropPreview() {
  val node1 =
      Tailcfg.Node(
          ComputedName = "My node 1",
          Addresses = listOf("100.100.100.100", "::1", "node.taiilscale.ts.net"),
          Hostinfo = Tailcfg.Hostinfo(OS = "Android"),
      )
  val node2 =
      Tailcfg.Node(
          ComputedName = "My node 2",
          Addresses = listOf("100.100.100.100", "::1", "node.taiilscale.ts.net"),
          Hostinfo = Tailcfg.Hostinfo(OS = "macOS"),
      )
  val outgoingFile =
      Ipn.OutgoingFile(Name = "thing", DeclaredSize = 100L).apply {
        uri = "content://example.test/shared/file.txt".toUri()
      }
  AppTheme {
    TaildropScreen(
        state =
            TaildropUIState(
                running = true,
                requestedTransfers = listOf(outgoingFile),
                totalSize = 200L,
                recent = listOf(node1),
                other = listOf(node2),
                transfers = listOf(outgoingFile),
            ),
        focusRequester = FocusRequester(),
        onShare = {},
        onToggle = {},
        transferStatusForPeer = { null },
        getType = { null },
    )
  }
}
