// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.viewModel.ExitNodePickerNav

@Composable
fun MullvadInfoView(nav: ExitNodePickerNav) {
  Scaffold(
      topBar = {
        Header(R.string.choose_mullvad_exit_node, onBack = nav.onNavigateBackToExitNodes)
      }) { innerPadding ->
        LazyColumn(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 48.dp),
            modifier = Modifier.padding(innerPadding)) {
              item {
                Image(
                    painter = painterResource(id = R.drawable.mullvad_logo),
                    contentDescription = stringResource(R.string.the_mullvad_vpn_logo))
              }
              item {
                Text(
                    stringResource(R.string.mullvad_info_title),
                    fontFamily = MaterialTheme.typography.titleLarge.fontFamily,
                    fontSize = MaterialTheme.typography.titleLarge.fontSize,
                    fontWeight = FontWeight.SemiBold)
              }
              item {
                Text(
                    stringResource(R.string.mullvad_info_explainer),
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center)
              }
            }
      }
}
