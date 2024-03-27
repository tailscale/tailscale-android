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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.AlwaysNeverUserDecidesSetting
import com.tailscale.ipn.mdm.BooleanSetting
import com.tailscale.ipn.mdm.ShowHideSetting
import com.tailscale.ipn.mdm.StringArraySetting
import com.tailscale.ipn.mdm.StringSetting
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.IpnViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MDMSettingsDebugView(nav: BackNavigation, model: IpnViewModel = viewModel()) {
  Scaffold(topBar = { Header(R.string.current_mdm_settings, onBack = nav.onBack) }) { innerPadding
    ->
    val mdmSettings = IpnViewModel.mdmSettings.collectAsState().value
    LazyColumn(modifier = Modifier.padding(innerPadding)) {
      itemsWithDividers(enumValues<BooleanSetting>()) { booleanSetting ->
        MDMSettingView(
            title = booleanSetting.localizedTitle,
            caption = booleanSetting.key,
            valueDescription = mdmSettings.get(booleanSetting).toString())
      }

      itemsWithDividers(enumValues<StringSetting>(), forceLeading = true) { stringSetting ->
        MDMSettingView(
            title = stringSetting.localizedTitle,
            caption = stringSetting.key,
            valueDescription = mdmSettings.get(stringSetting).toString())
      }

      itemsWithDividers(enumValues<ShowHideSetting>(), forceLeading = true) { showHideSetting ->
        MDMSettingView(
            title = showHideSetting.localizedTitle,
            caption = showHideSetting.key,
            valueDescription = mdmSettings.get(showHideSetting).toString())
      }

      itemsWithDividers(enumValues<AlwaysNeverUserDecidesSetting>(), forceLeading = true) {
          anuSetting ->
        MDMSettingView(
            title = anuSetting.localizedTitle,
            caption = anuSetting.key,
            valueDescription = mdmSettings.get(anuSetting).toString())
      }

      itemsWithDividers(enumValues<StringArraySetting>(), forceLeading = true) { stringArraySetting
        ->
        MDMSettingView(
            title = stringArraySetting.localizedTitle,
            caption = stringArraySetting.key,
            valueDescription = mdmSettings.get(stringArraySetting).toString())
      }
    }
  }
}

@Composable
fun MDMSettingView(title: String, caption: String, valueDescription: String) {
  ListItem(
      headlineContent = { Text(title, maxLines = 3) },
      supportingContent = {
        Text(
            caption,
            fontSize = MaterialTheme.typography.labelSmall.fontSize,
            color = MaterialTheme.colorScheme.tertiary,
            fontFamily = FontFamily.Monospace)
      },
      trailingContent = {
        Text(
            valueDescription,
            color = MaterialTheme.colorScheme.secondary,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            fontWeight = FontWeight.SemiBold)
      })
}
