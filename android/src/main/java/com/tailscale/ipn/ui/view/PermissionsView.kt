// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Permissions
import com.tailscale.ipn.ui.util.itemsWithDividers

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsView(nav: BackNavigation, openApplicationSettings: () -> Unit) {
  Scaffold(topBar = { Header(titleRes = R.string.permissions, onBack = nav.onBack) }) { innerPadding
    ->
    val permissions = Permissions.all
    val permissionStates =
        rememberMultiplePermissionsState(permissions = permissions.map { it.name })
    val permissionsWithStates = permissions.zip(permissionStates.permissions)

    LazyColumn(modifier = Modifier.padding(innerPadding)) {
      itemsWithDividers(permissionsWithStates) { (permission, state) ->
        var modifier: Modifier = Modifier
        if (!state.status.isGranted) {
          modifier = modifier.clickable { openApplicationSettings() }
        }
        ListItem(
            modifier = modifier,
            leadingContent = {
              Icon(
                  if (state.status.isGranted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                  modifier = Modifier.size(24.dp),
                  contentDescription =
                      stringResource(if (state.status.isGranted) R.string.ok else R.string.warning))
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
