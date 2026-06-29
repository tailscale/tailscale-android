// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.util.Lists.SectionDivider
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.TaildropViewModel
import com.tailscale.ipn.ui.viewModel.TaildropViewModelFactory
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.util.TdPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

@Composable
fun TaildropView(
    requestedTransfers: StateFlow<List<Ipn.OutgoingFile>>,
    applicationScope: CoroutineScope,
    viewModel: TaildropViewModel =
        viewModel(factory = TaildropViewModelFactory(requestedTransfers, applicationScope)),
) {
  val TAG = "TaildropView"
  val focusRequester = remember { FocusRequester() }

  // Automatically request focus when the composable is displayed
  LaunchedEffect(Unit) {
    try {
      focusRequester.requestFocus()
    } catch (e: Exception) {
      TSLog.w(TAG, "Focus request failed: ${e.message}")
    }
  }

  Column(modifier = Modifier.focusRequester(focusRequester).focusable()) {
    val showDialog = viewModel.showDialog.collectAsState().value

    showDialog?.let { ErrorDialog(type = it, action = { viewModel.showDialog.set(null) }) }

    FileShareHeader(
        fileTransfers = requestedTransfers.collectAsState().value,
        totalSize = viewModel.totalSize,
    )

    when (viewModel.state.collectAsState().value) {
      Ipn.State.Running -> {
        val recent by viewModel.recentPeers.collectAsState()
        val other by viewModel.otherPeers.collectAsState()
        val context = LocalContext.current
        FileSharePeerList(
            recent = recent,
            other = other,
            stateViewGenerator = { peerId -> viewModel.TrailingContentForPeer(peerId = peerId) },
            onShare = { viewModel.share(context, it) },
        )
      }
      else -> {
        FileShareConnectView { viewModel.startVPN() }
      }
    }
  }
}

@Composable
fun FileSharePeerList(
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
fun FileShareConnectView(onToggle: () -> Unit) {
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
fun FileShareHeader(fileTransfers: List<Ipn.OutgoingFile>, totalSize: Long) {
  Column(modifier = Modifier.padding(horizontal = 12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconForTransfer(fileTransfers)
      Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        val payload = fileTransfers.singleOrNull()?.tdPayload
        when {
          fileTransfers.isEmpty() ->
              Text(
                  stringResource(R.string.no_files_to_share),
                  style = MaterialTheme.typography.titleMedium,
              )
          payload != null ->
              // Show the actual text/URL instead of the opaque envelope filename.
              Text(
                  text = payload.content,
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
fun IconForTransfer(transfers: List<Ipn.OutgoingFile>) {
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
      val payload = only.tdPayload
      if (payload != null) {
        val resource =
            if (payload.kind == TdPayload.Kind.URL) R.drawable.link else R.drawable.single_file
        Icon(
            painter = painterResource(resource),
            contentDescription = payload.kind.name.lowercase(),
            modifier = Modifier.size(40.dp),
        )
        return
      }
      // Show a thumbnail for single image shares.
      val context = LocalContext.current
      context.contentResolver.getType(only.uri)?.let {
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
