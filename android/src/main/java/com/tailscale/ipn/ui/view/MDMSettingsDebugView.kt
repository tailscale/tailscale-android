// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.AlwaysNeverUserDecidesSetting
import com.tailscale.ipn.mdm.BooleanSetting
import com.tailscale.ipn.mdm.ShowHideSetting
import com.tailscale.ipn.mdm.StringArraySetting
import com.tailscale.ipn.mdm.StringSetting
import com.tailscale.ipn.ui.viewModel.IpnViewModel
import com.tailscale.ipn.ui.util.defaultPaddingModifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MDMSettingsDebugView(model: IpnViewModel = viewModel()) {
    Scaffold(
        topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                titleContentColor = MaterialTheme.colorScheme.primary,
            ), title = {
                Text(stringResource(R.string.current_mdm_settings))
            })
        },
    ) { innerPadding ->
        val mdmSettings = IpnViewModel.mdmSettings.collectAsState().value
        LazyColumn(modifier = Modifier.padding(innerPadding)) {
            items(enumValues<BooleanSetting>()) { booleanSetting ->
                MDMSettingView(
                    title = booleanSetting.localizedTitle,
                    caption = booleanSetting.key,
                    valueDescription = mdmSettings.get(booleanSetting).toString()
                )
            }

            items(enumValues<StringSetting>()) { stringSetting ->
                MDMSettingView(
                    title = stringSetting.localizedTitle,
                    caption = stringSetting.key,
                    valueDescription = mdmSettings.get(stringSetting).toString()
                )
            }

            items(enumValues<ShowHideSetting>()) { showHideSetting ->
                MDMSettingView(
                    title = showHideSetting.localizedTitle,
                    caption = showHideSetting.key,
                    valueDescription = mdmSettings.get(showHideSetting).toString()
                )
            }

            items(enumValues<AlwaysNeverUserDecidesSetting>()) { anuSetting ->
                MDMSettingView(
                    title = anuSetting.localizedTitle,
                    caption = anuSetting.key,
                    valueDescription = mdmSettings.get(anuSetting).toString()
                )
            }

            items(enumValues<StringArraySetting>()) { stringArraySetting ->
                MDMSettingView(
                    title = stringArraySetting.localizedTitle,
                    caption = stringArraySetting.key,
                    valueDescription = mdmSettings.get(stringArraySetting).toString()
                )
            }
        }
    }

}

@Composable
fun MDMSettingView(title: String, caption: String, valueDescription: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = defaultPaddingModifier().fillMaxWidth()
    ) {
        Column {
            Text(title, maxLines = 3)
            Text(
                caption,
                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                color = MaterialTheme.colorScheme.tertiary,
                fontFamily = FontFamily.Monospace
            )
        }

        Text(
            valueDescription,
            color = MaterialTheme.colorScheme.secondary,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            fontWeight = FontWeight.SemiBold
        )
    }
}