// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.StringSetting

@Composable
fun ManagedByView(mdmSettings: MDMSettings) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            verticalArrangement = Arrangement.spacedBy(
                space = 20.dp, alignment = Alignment.CenterVertically
            ),
            horizontalAlignment = Alignment.Start,
            modifier = Modifier
                .fillMaxWidth()
                .safeContentPadding()
        ) {
            mdmSettings.get(StringSetting.ManagedByOrganizationName)?.let {
                Text(stringResource(R.string.managed_by_explainer_orgName, it))
            } ?: run {
                Text(stringResource(R.string.managed_by_explainer))
            }
            mdmSettings.get(StringSetting.ManagedByCaption)?.let {
                if (it.isNotEmpty()) {
                    Text(it)
                }
            }
            mdmSettings.get(StringSetting.ManagedByURL)?.let {
                OpenURLButton(stringResource(R.string.open_support), it)
            }
        }
    }
}