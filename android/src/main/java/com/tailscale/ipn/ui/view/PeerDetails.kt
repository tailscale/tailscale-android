// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.theme.short
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.PeerDetailsViewModel
import com.tailscale.ipn.ui.viewModel.PeerDetailsViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerDetails(
    nav: BackNavigation,
    nodeId: String,
    model: PeerDetailsViewModel =
        viewModel(factory = PeerDetailsViewModelFactory(nodeId, LocalContext.current.filesDir))
) {
  model.netmap.collectAsState().value?.let { netmap ->
    model.node.collectAsState().value?.let { node ->
      Scaffold(
          topBar = {
            Header(
                title = {
                  Column {
                    Text(
                        text = node.displayName,
                        style = MaterialTheme.typography.titleMedium.short,
                        color = MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Box(
                          modifier =
                              Modifier.size(8.dp)
                                  .background(
                                      color = node.connectedColor(netmap),
                                      shape = RoundedCornerShape(percent = 50))) {}
                      Spacer(modifier = Modifier.size(8.dp))
                      Text(
                          text = stringResource(id = node.connectedStrRes(netmap)),
                          style = MaterialTheme.typography.bodyMedium.short,
                          color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                  }
                },
                onBack = { nav.onBack() })
          },
      ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
        ) {
          item(key = "tailscaleAddresses") {
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surface, shape = RectangleShape)) {
                  Text(
                      modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                      text = stringResource(R.string.tailscale_addresses),
                      style = MaterialTheme.typography.titleSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
          }

          itemsWithDividers(node.displayAddresses, key = { it.address }) {
            AddressRow(address = it.address, type = it.typeString)
          }

          item(key = "infoDivider") { Lists.SectionDivider() }

          itemsWithDividers(node.info, key = { "info_${it.titleRes}" }) {
            ValueRow(title = stringResource(id = it.titleRes), value = it.value.getString())
          }
        }
      }
    }
  }
}

@Composable
fun AddressRow(address: String, type: String) {
  val localClipboardManager = LocalClipboardManager.current

  ListItem(
      modifier = Modifier.clickable { localClipboardManager.setText(AnnotatedString(address)) },
      colors = MaterialTheme.colorScheme.listItem,
      headlineContent = { Text(text = address) },
      supportingContent = { Text(text = type) },
      trailingContent = {
        // TODO: there is some overlap with other uses of clipboard, DRY
        Icon(painter = painterResource(id = R.drawable.clipboard), null)
      })
}

@Composable
fun ValueRow(title: String, value: String) {
  ListItem(
      colors = MaterialTheme.colorScheme.listItem,
      headlineContent = { Text(text = title) },
      supportingContent = { Text(text = value) })
}
