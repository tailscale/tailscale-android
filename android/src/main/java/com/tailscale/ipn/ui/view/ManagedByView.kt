// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.components.Header
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.AndroidTVUtil

@Composable
fun ManagedByView(backToSettings: () -> Unit) {
  val organization by MDMSettings.managedByOrganizationName.flow.collectAsStateWithLifecycle()
  val caption by MDMSettings.managedByCaption.flow.collectAsStateWithLifecycle()
  val supportUrl by MDMSettings.managedByURL.flow.collectAsStateWithLifecycle()

  val uiState =
      ManagedByUIState(
          organizationName = organization.value,
          caption = caption.value,
          supportUrl = supportUrl.value,
      )

  ManagedByScreen(
      state = uiState,
      backToSettings = backToSettings,
      isTv = AndroidTVUtil.isAndroidTV(),
  )
}

private data class ManagedByUIState(
    val organizationName: String?,
    val caption: String?,
    val supportUrl: String?,
)

@Composable
private fun ManagedByScreen(
    state: ManagedByUIState,
    isTv: Boolean,
    backToSettings: () -> Unit,
) {
  Scaffold(
      topBar = {
        Header(
            R.string.managed_by,
            onBack = backToSettings,
            isTv = isTv,
        )
      }
  ) { innerPadding ->
    Column(
        verticalArrangement =
            Arrangement.spacedBy(space = 20.dp, alignment = Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier.padding(innerPadding)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
    ) {
      state.organizationName?.let {
        Text(stringResource(R.string.managed_by_explainer_orgName, it))
      } ?: run { Text(stringResource(R.string.managed_by_explainer)) }

      state.caption?.takeIf { it.isNotEmpty() }?.let { Text(it) }

      state.supportUrl
          ?.takeIf { it.isNotEmpty() }
          ?.let { OpenURLButton(stringResource(R.string.open_support), it) }
    }
  }
}

@Preview(showSystemUi = true)
@Composable
fun ManagedByPreview() {
  AppTheme {
    ManagedByScreen(
        state =
            ManagedByUIState(
                organizationName = "Tailscale",
                caption = "Your organization requires this",
                supportUrl = "https://tailscale.com",
            ),
        isTv = false,
        backToSettings = {},
    )
  }
}
