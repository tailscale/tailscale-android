// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.DebugPolicies
import com.tailscale.ipn.mdm.MDMSetting
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.components.Header
import com.tailscale.ipn.ui.util.AndroidTVUtil
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.IpnViewModel

@Composable
fun MDMSettingsDebugView(
    backToSettings: () -> Unit,
    @Suppress("UNUSED_PARAMETER") model: IpnViewModel = viewModel(),
) {
  val debugPoliciesActive by DebugPolicies.isActive.collectAsState()

  Scaffold(
      topBar = {
        Header(
            R.string.current_mdm_settings,
            onBack = backToSettings,
            isTv = AndroidTVUtil.isAndroidTV(),
        )
      }
  ) { innerPadding ->
    LazyColumn(modifier = Modifier.padding(innerPadding)) {
      if (debugPoliciesActive) {
        item {
          Text(
              stringResource(R.string.mdm_debug_policies_active),
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
          )
        }
      }
      itemsWithDividers(MDMSettings.allSettings.sortedBy { "${it::class.java.name}|${it.key}" }) {
          setting ->
        MDMSettingView(setting)
      }
    }
  }
}

@Composable
fun MDMSettingView(setting: MDMSetting<*>) {
  val value by setting.flow.collectAsState()
  ListItem(
      headlineContent = { Text(setting.localizedTitle, maxLines = 3) },
      supportingContent = {
        Text(
            setting.key,
            fontSize = MaterialTheme.typography.labelSmall.fontSize,
            fontFamily = FontFamily.Monospace,
        )
      },
      trailingContent = {
        Text(
            if (value.isSet) value.value.toString() else "[not set]",
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            fontWeight = FontWeight.SemiBold,
        )
      },
  )
}
