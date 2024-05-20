// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.viewModel.ExitNodePickerNav
import com.tailscale.ipn.ui.viewModel.IpnViewModel

@Composable
fun RunExitNodeView(nav: ExitNodePickerNav, model: IpnViewModel = viewModel()) {
  val isRunningExitNode by model.isRunningExitNode.collectAsState()

  Scaffold(
      topBar = { Header(R.string.run_as_exit_node, onBack = nav.onNavigateBackToExitNodes) }) {
          innerPadding ->
        LoadingIndicator.Wrap {
          Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement =
                  Arrangement.spacedBy(24.dp, alignment = Alignment.CenterVertically),
              modifier =
                  Modifier.padding(innerPadding)
                      .padding(24.dp)
                      .fillMaxHeight()
                      .verticalScroll(rememberScrollState())) {
                RunExitNodeGraphic()

                if (isRunningExitNode) {
                  Text(
                      stringResource(R.string.running_as_exit_node),
                      fontFamily = MaterialTheme.typography.titleLarge.fontFamily,
                      fontSize = MaterialTheme.typography.titleLarge.fontSize,
                      fontWeight = FontWeight.SemiBold)
                  Text(stringResource(R.string.run_exit_node_explainer_running))
                } else {
                  Text(
                      stringResource(R.string.run_this_device_as_an_exit_node),
                      fontFamily = MaterialTheme.typography.titleLarge.fontFamily,
                      fontSize = MaterialTheme.typography.titleLarge.fontSize,
                      fontWeight = FontWeight.SemiBold)
                  Text(stringResource(R.string.run_exit_node_explainer))
                }
                Text(stringResource(R.string.run_exit_node_caution))

                Button(onClick = { model.setRunningExitNode(!isRunningExitNode) }) {
                  if (isRunningExitNode) {
                    Text(stringResource(R.string.stop_running_as_exit_node))
                  } else {
                    Text(stringResource(R.string.start_running_as_exit_node))
                  }
                }
                Spacer(modifier = Modifier.size(24.dp))
              }
        }
      }
}

@Composable
fun RunExitNodeGraphic() {
  @Composable
  fun ArrowForward() {
    Icon(
        Icons.AutoMirrored.Outlined.ArrowForward,
        "Arrow Forward",
        modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }

  Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(vertical = 18.dp)) {
        Icon(
            painter = painterResource(id = R.drawable.computer),
            "Computer icon",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(36.dp))
        ArrowForward()
        Icon(
            painter = painterResource(id = R.drawable.android),
            "Android icon",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(36.dp))
        ArrowForward()
        Icon(
            painter = painterResource(id = R.drawable.globe),
            "Globe icon",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(36.dp))
      }
}
