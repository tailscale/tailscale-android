// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSetting
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.IpnViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MDMSettingsDebugView(
    backToSettings: BackNavigation,
    @Suppress("UNUSED_PARAMETER") model: IpnViewModel = viewModel()
) {
  Scaffold(topBar = { Header(R.string.current_mdm_settings, onBack = backToSettings) }) {
      innerPadding ->
    LazyColumn(modifier = Modifier.padding(innerPadding)) {
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
            fontFamily = FontFamily.Monospace)
      },
      trailingContent = {
        Text(
            if (value.isSet) value.value.toString() else "[not set]",
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            fontWeight = FontWeight.SemiBold)
      })
}
