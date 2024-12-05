// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.text.format.Formatter
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

@Composable
fun TaildropView(
    requestedTransfers: StateFlow<List<Ipn.OutgoingFile>>,
    applicationScope: CoroutineScope,
    viewModel: TaildropViewModel =
        viewModel(factory = TaildropViewModelFactory(requestedTransfers, applicationScope))
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

  Scaffold(contentWindowInsets = WindowInsets.statusBars, topBar = { Header(R.string.share) }) {
      paddingInsets ->
    Column(modifier = Modifier.focusRequester(focusRequester).focusable().padding(paddingInsets)) {
      val showDialog = viewModel.showDialog.collectAsState().value

      showDialog?.let { ErrorDialog(type = it, action = { viewModel.showDialog.set(null) }) }

      FileShareHeader(
          fileTransfers = requestedTransfers.collectAsState().value,
          totalSize = viewModel.totalSize)

      when (viewModel.state.collectAsState().value) {
        Ipn.State.Running -> {
          val peers by viewModel.myPeers.collectAsState()
          val context = LocalContext.current
          FileSharePeerList(
              peers = peers,
              stateViewGenerator = { peerId -> viewModel.TrailingContentForPeer(peerId = peerId) },
              onShare = { viewModel.share(context, it) })
        }
        else -> {
          FileShareConnectView { viewModel.startVPN() }
        }
      }
    }
  }
}

@Composable
fun FileSharePeerList(
    peers: List<Tailcfg.Node>,
    stateViewGenerator: @Composable (String) -> Unit,
    onShare: (Tailcfg.Node) -> Unit
) {
  SectionDivider(stringResource(R.string.my_devices))

  when (peers.isEmpty()) {
    true -> {
      Column(
          modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.no_devices_to_share_with),
                style = MaterialTheme.typography.titleMedium)
          }
    }
    false -> {
      LazyColumn {
        peers.forEach { peer ->
          item {
            PeerView(
                peer = peer,
                onClick = { onShare(peer) },
                subtitle = { peer.Hostinfo.OS ?: "" },
                trailingContent = { stateViewGenerator(peer.StableID) })
          }
        }
      }
    }
  }
}

@Composable
fun FileShareConnectView(onToggle: () -> Unit) {
  Column(
      modifier = Modifier.padding(horizontal = 16.dp).fillMaxHeight(),
      verticalArrangement = Arrangement.spacedBy(6.dp, alignment = Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.connect_to_your_tailnet_to_share_files),
            style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.size(1.dp))
        PrimaryActionButton(onClick = onToggle) {
          Text(
              text = stringResource(id = R.string.connect),
              fontSize = MaterialTheme.typography.titleMedium.fontSize)
        }
      }
}

@Composable
fun FileShareHeader(fileTransfers: List<Ipn.OutgoingFile>, totalSize: Long) {
  Column(modifier = Modifier.padding(horizontal = 12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconForTransfer(fileTransfers)
      Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        when (fileTransfers.isEmpty()) {
          true ->
              Text(
                  stringResource(R.string.no_files_to_share),
                  style = MaterialTheme.typography.titleMedium)
          false -> {

            when (fileTransfers.size) {
              1 -> Text(fileTransfers[0].Name, style = MaterialTheme.typography.titleMedium)
              else ->
                  Text(
                      stringResource(R.string.file_count, fileTransfers.size),
                      style = MaterialTheme.typography.titleMedium)
            }
          }
        }
        val size = Formatter.formatFileSize(LocalContext.current, totalSize.toLong())
        Text(
            size,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.secondary)
      }
    }
  }
}

@Composable
fun IconForTransfer(transfers: List<Ipn.OutgoingFile>) {
  // (jonathan) TODO: Thumbnails?
  when (transfers.size) {
    0 ->
        Icon(
            painter = painterResource(R.drawable.warning),
            contentDescription = "no files",
            modifier = Modifier.size(32.dp))
    1 -> {
      // Show a thumbnail for single image shares.
      val context = LocalContext.current
      context.contentResolver.getType(transfers[0].uri)?.let {
        if (it.startsWith("image/")) {
          AsyncImage(
              model = transfers[0].uri,
              contentDescription = "one file",
              modifier = Modifier.size(40.dp))
          return
        }

        Icon(
            painter = painterResource(R.drawable.single_file),
            contentDescription = "files",
            modifier = Modifier.size(40.dp))
      }
    }
    else ->
        Icon(
            painter = painterResource(R.drawable.single_file),
            contentDescription = "files",
            modifier = Modifier.size(40.dp))
  }
}
