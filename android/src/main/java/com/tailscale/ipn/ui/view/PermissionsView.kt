// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Permissions
import com.tailscale.ipn.ui.theme.success
import com.tailscale.ipn.ui.util.itemsWithDividers

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsView(backToSettings: BackNavigation, openApplicationSettings: () -> Unit) {
  val permissions = Permissions.withGrantedStatus
  Scaffold(topBar = { Header(titleRes = R.string.permissions, onBack = backToSettings) }) {
      innerPadding ->
    LazyColumn(modifier = Modifier.padding(innerPadding)) {
      itemsWithDividers(permissions) { (permission, granted) ->
        ListItem(
            modifier = Modifier.clickable { openApplicationSettings() },
            leadingContent = {
              Icon(
                  if (granted) painterResource(R.drawable.check_circle)
                  else painterResource(R.drawable.xmark_circle),
                  tint =
                      if (granted) MaterialTheme.colorScheme.success
                      else MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(24.dp),
                  contentDescription =
                      stringResource(if (granted) R.string.ok else R.string.warning))
            },
            headlineContent = {
              Text(stringResource(permission.title), style = MaterialTheme.typography.titleMedium)
            },
            supportingContent = { Text(stringResource(permission.description)) },
        )
      }
    }
  }
}
