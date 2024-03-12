// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.tailscale.ipn.ui.viewModel.PeerDetailsViewModel


@Composable
fun PeerDetails(viewModel: PeerDetailsViewModel) {
    Column {
        Text(text = "Future Home Of Peer Details for node id ${viewModel.nodeId}")
    }
}