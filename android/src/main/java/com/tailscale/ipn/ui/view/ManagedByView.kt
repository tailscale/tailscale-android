// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.viewModel.IpnViewModel

@Composable
fun ManagedByView(backToSettings: BackNavigation, model: IpnViewModel = viewModel()) {
  Scaffold(topBar = { Header(R.string.managed_by, onBack = backToSettings) }) { innerPadding ->
    Column(
        verticalArrangement =
            Arrangement.spacedBy(space = 20.dp, alignment = Alignment.CenterVertically),
        horizontalAlignment = Alignment.Start,
        modifier = Modifier.fillMaxWidth().safeContentPadding().verticalScroll(rememberScrollState())) {
          val managedByOrganization =
              MDMSettings.managedByOrganizationName.flow.collectAsState().value
          val managedByCaption = MDMSettings.managedByCaption.flow.collectAsState().value
          val managedByURL = MDMSettings.managedByURL.flow.collectAsState().value
          managedByOrganization?.let {
            Text(stringResource(R.string.managed_by_explainer_orgName, it))
          } ?: run { Text(stringResource(R.string.managed_by_explainer)) }
          managedByCaption?.let {
            if (it.isNotEmpty()) {
              Text(it)
            }
          }
          managedByURL?.let { OpenURLButton(stringResource(R.string.open_support), it) }
        }
  }
}

@Preview
@Composable
fun ManagedByViewPreview() {
  val vm = IpnViewModel()
  ManagedByView(backToSettings = {}, vm)
}
