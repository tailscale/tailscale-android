// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause


package com.tailscale.ipn.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.settingsRowModifier
import com.tailscale.ipn.ui.viewModel.PeerDetailsViewModel
import com.tailscale.ipn.ui.viewModel.PeerDetailsViewModelFactory


@Composable
fun PeerDetails(
        nodeId: String, model: PeerDetailsViewModel = viewModel(
                factory = PeerDetailsViewModelFactory(nodeId)
        )
) {
    Scaffold(topBar = {
        Column(
                modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
        ) {
            Text(
                    text = model.nodeName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier
                        .size(8.dp)
                        .background(
                                color = model.connectedColor,
                                shape = RoundedCornerShape(percent = 50)
                        )
                ) {}
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                        text = stringResource(id = model.connectedStrRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }) { innerPadding ->
        Column(
                modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxHeight()
        ) {

            Text(
                    text = stringResource(id = R.string.addresses_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
            )

            Column(modifier = settingsRowModifier()) {
                model.addresses.forEach {
                    AddressRow(address = it.address, type = it.typeString)
                }
            }

            Spacer(modifier = Modifier.size(16.dp))

            Column(modifier = settingsRowModifier()) {
                model.info.forEach {
                    ValueRow(title = stringResource(id = it.titleRes), value = it.value.getString())
                }
            }
        }
    }
}

@Composable
fun AddressRow(address: String, type: String) {
    val localClipboardManager = LocalClipboardManager.current

    Row(
            modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable(onClick = { localClipboardManager.setText(AnnotatedString(address)) })
    ) {
        Column {
            Text(text = address, style = MaterialTheme.typography.titleMedium)
            Text(text = type, style = MaterialTheme.typography.bodyMedium)
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Icon(Icons.Outlined.Share, null)
        }
    }
}

@Composable
fun ValueRow(title: String, value: String) {
    Row(
            modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .fillMaxWidth()
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
