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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.ui.viewModel.PeerDetailsViewModel


@Composable
fun PeerDetails(viewModel: PeerDetailsViewModel) {

    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        Column(modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = viewModel.nodeName, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier
                        .size(8.dp)
                        .background(color = viewModel.connectedColor, shape = RoundedCornerShape(percent = 50))) {}
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = viewModel.connectedStr, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(modifier = Modifier.size(8.dp))

        Text(text = "TAILSCALE ADDRESSES", style = MaterialTheme.typography.titleMedium)

        Column(modifier = Modifier
                .clip(shape = RoundedCornerShape(8.dp))
                .background(color = MaterialTheme.colorScheme.secondaryContainer)
                .fillMaxWidth()) {
            viewModel.addresses.forEach {
                AddressRow(address = it.address, type = it.typeString)
            }
        }

        Spacer(modifier = Modifier.size(16.dp))

        Column(modifier = Modifier
                .clip(shape = RoundedCornerShape(8.dp))
                .background(color = MaterialTheme.colorScheme.secondaryContainer)
                .fillMaxWidth()) {
            viewModel.info.forEach {
                ValueRow(title = it.title, value = it.value)
            }
        }
    }
}

@Composable
fun AddressRow(address: String, type: String) {
    val localClipboardManager = LocalClipboardManager.current

    Row(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(onClick = { localClipboardManager.setText(AnnotatedString(address)) })) {
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
    Row(modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
