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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.components.Header
import com.tailscale.ipn.ui.dataSource.ExitNodePrefsDataSource
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.AndroidTVUtil
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.viewModel.ExitNodePickerNav
import com.tailscale.ipn.ui.viewModel.RunExitNodeViewModel
import com.tailscale.ipn.ui.viewModel.RunExitNodeViewModelFactory
import kotlinx.coroutines.launch

@Composable
fun RunExitNodeView(
    exitNodePickerNav: ExitNodePickerNav,
    dataSource: ExitNodePrefsDataSource,
) {
  val factory = remember(dataSource) { RunExitNodeViewModelFactory(dataSource) }
  val runExitNodeViewModel: RunExitNodeViewModel = viewModel(factory = factory)
  val isRunningExitNode by runExitNodeViewModel.isRunningExitNode.collectAsStateWithLifecycle()
  val actionScope = rememberCoroutineScope()

  RunExitNodeScreen(
      state =
          RunExitNodeUiState(
              isRunningExitNode = isRunningExitNode,
              toggleExitNode = {
                val shouldRun = !isRunningExitNode
                actionScope.launch {
                  LoadingIndicator.start()
                  try {
                    runExitNodeViewModel.setRunningExitNode(isOn = shouldRun)
                  } finally {
                    LoadingIndicator.stop()
                  }
                }
              },
          ),
      nav = exitNodePickerNav,
      isTv = AndroidTVUtil.isAndroidTV(),
  )
}

@Composable
private fun RunExitNodeGraphic() {
  @Composable
  fun ArrowForward() {
    Icon(
        Icons.AutoMirrored.Outlined.ArrowForward,
        "Arrow Forward",
        modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }

  Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(vertical = 18.dp),
  ) {
    Icon(
        painter = painterResource(id = R.drawable.computer),
        "Computer icon",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(36.dp),
    )
    ArrowForward()
    Icon(
        painter = painterResource(id = R.drawable.android),
        "Android icon",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(36.dp),
    )
    ArrowForward()
    Icon(
        painter = painterResource(id = R.drawable.globe),
        "Globe icon",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(36.dp),
    )
  }
}

private data class RunExitNodeUiState(
    val isRunningExitNode: Boolean,
    val toggleExitNode: () -> Unit,
)

@Composable
private fun RunExitNodeScreen(
    state: RunExitNodeUiState,
    nav: ExitNodePickerNav,
    isTv: Boolean,
) {
  Scaffold(
      topBar = {
        Header(
            R.string.run_as_exit_node,
            onBack = nav.onNavigateBackToExitNodes,
            isTv = isTv,
        )
      }
  ) { innerPadding ->
    LoadingIndicator.Wrap {
      Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(24.dp, alignment = Alignment.CenterVertically),
          modifier =
              Modifier.padding(innerPadding)
                  .padding(24.dp)
                  .fillMaxHeight()
                  .verticalScroll(rememberScrollState()),
      ) {
        RunExitNodeGraphic()

        if (state.isRunningExitNode) {
          Text(
              stringResource(R.string.running_as_exit_node),
              fontFamily = MaterialTheme.typography.titleLarge.fontFamily,
              fontSize = MaterialTheme.typography.titleLarge.fontSize,
              fontWeight = FontWeight.SemiBold,
          )
          Text(stringResource(R.string.run_exit_node_explainer_running))
        } else {
          Text(
              stringResource(R.string.run_this_device_as_an_exit_node),
              fontFamily = MaterialTheme.typography.titleLarge.fontFamily,
              fontSize = MaterialTheme.typography.titleLarge.fontSize,
              fontWeight = FontWeight.SemiBold,
          )
          Text(stringResource(R.string.run_exit_node_explainer))
        }
        Text(stringResource(R.string.run_exit_node_caution))

        Button(onClick = state.toggleExitNode) {
          if (state.isRunningExitNode) {
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

@Preview(showSystemUi = true)
@Composable
private fun RunExitNodePreview() {
  var isRunning by remember { mutableStateOf(false) }

  AppTheme {
    RunExitNodeScreen(
        state =
            RunExitNodeUiState(
                isRunningExitNode = isRunning,
                toggleExitNode = { isRunning = !isRunning },
            ),
        nav =
            ExitNodePickerNav(
                onNavigateBackHome = {},
                onNavigateBackToExitNodes = {},
                onNavigateToMullvad = {},
                onNavigateToMullvadInfo = {},
                onNavigateBackToMullvad = {},
                onNavigateToMullvadCountry = {},
                onNavigateToRunAsExitNode = {},
            ),
        isTv = false,
    )
  }
}
