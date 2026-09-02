// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.viewModel.PendingTaildropViewModel
import com.tailscale.ipn.util.InlineShare

// Bottom sheet of pending Taildrop items; mirrors iOS InlineShareListSheet.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InlineShareListSheet(viewModel: PendingTaildropViewModel) {
  val items by viewModel.pendingItems.collectAsState()
  val context = LocalContext.current

  Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
          Text(
              text = stringResource(R.string.taildrop_received_sheet_title),
              style = MaterialTheme.typography.titleMedium,
              modifier = Modifier.weight(1f))
          TextButton(onClick = { viewModel.isPresentingPendingItemsList.value = false }) {
            Text(text = stringResource(R.string.taildrop_done))
          }
        }

    HorizontalDivider()

    LazyColumn {
      items.forEachIndexed { index, item ->
        item(key = item.id) {
          PendingItemRow(
              item = item,
              onConsume = { viewModel.consume(context, item) },
              onDismiss = { viewModel.dismiss(context, item) },
              onOpenFolder = { viewModel.openTaildropFolder(context) })
          if (index < items.size - 1) HorizontalDivider()
        }
      }
    }
  }
}

@Composable
private fun PendingItemRow(
    item: PendingTaildropViewModel.PendingTaildropItem,
    onConsume: () -> Unit,
    onDismiss: () -> Unit,
    onOpenFolder: () -> Unit,
) {
  Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier =
          Modifier.fillMaxWidth()
              .clickable { onConsume() }
              .padding(horizontal = 16.dp, vertical = 12.dp),
  ) {
    Icon(
        painter = painterResource(id = iconFor(item)),
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.size(12.dp))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
          text = primaryText(item),
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      Text(
          text = secondaryText(item),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    when (item) {
      is PendingTaildropViewModel.PendingTaildropItem.InlineShareItem ->
          IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.taildrop_dismiss),
            )
          }
      is PendingTaildropViewModel.PendingTaildropItem.File ->
          IconButton(onClick = onOpenFolder) {
            Icon(
                painter = painterResource(id = R.drawable.baseline_folder_open_24),
                contentDescription = stringResource(R.string.taildrop_open_folder),
            )
          }
    }
  }
}

private fun iconFor(item: PendingTaildropViewModel.PendingTaildropItem): Int =
    when (item) {
      is PendingTaildropViewModel.PendingTaildropItem.File -> R.drawable.single_file
      is PendingTaildropViewModel.PendingTaildropItem.InlineShareItem ->
          if (item.share.kind == InlineShare.Kind.URL) R.drawable.link else R.drawable.single_file
    }

@Composable
private fun primaryText(item: PendingTaildropViewModel.PendingTaildropItem): String =
    when (item) {
      is PendingTaildropViewModel.PendingTaildropItem.File -> item.partial.Name
      is PendingTaildropViewModel.PendingTaildropItem.InlineShareItem ->
          when (item.share.kind) {
            InlineShare.Kind.URL -> item.share.content
            InlineShare.Kind.TEXT -> item.share.content.take(80).replace("\n", " ")
          }
    }

@Composable
private fun secondaryText(item: PendingTaildropViewModel.PendingTaildropItem): String =
    when (item) {
      is PendingTaildropViewModel.PendingTaildropItem.File ->
          stringResource(R.string.taildrop_tap_to_open)
      is PendingTaildropViewModel.PendingTaildropItem.InlineShareItem ->
          if (item.share.kind == InlineShare.Kind.URL) stringResource(R.string.taildrop_tap_to_open)
          else stringResource(R.string.taildrop_tap_to_copy)
    }
