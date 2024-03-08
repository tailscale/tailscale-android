// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause


package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.tailscale.ipn.ui.viewModel.SettingsViewModel


@Composable
fun Settings(viewModel: SettingsViewModel) {
    Column {
        Text(text = "Future Home of Settings")
    }
}
