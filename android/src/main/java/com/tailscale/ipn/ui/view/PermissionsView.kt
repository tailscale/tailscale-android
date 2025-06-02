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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Permissions
import com.tailscale.ipn.ui.util.friendlyDirName
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.PermissionsViewModel

@Composable
fun PermissionsView(
    backToSettings: BackNavigation,
    navToTaildropDirView: () -> Unit,
    navToNotificationsView: () -> Unit,
    permissionsViewModel: PermissionsViewModel = viewModel()
) {
  val permissions = Permissions.withGrantedStatus

  Scaffold(topBar = { Header(titleRes = R.string.permissions, onBack = backToSettings) }) {
      innerPadding ->
    LazyColumn(modifier = Modifier.padding(innerPadding)) {
      // Existing Android runtime permissions
      itemsWithDividers(permissions) { (permission, granted) ->
        ListItem(
            modifier = Modifier.clickable { navToNotificationsView() },
            leadingContent = {
              Icon(
                  painterResource(R.drawable.baseline_notifications_none_24),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(24.dp),
                  contentDescription =
                      stringResource(if (granted) R.string.ok else R.string.warning))
            },
            headlineContent = {
              Text(stringResource(permission.title), style = MaterialTheme.typography.titleMedium)
            },
            supportingContent = {
              if (granted) Text(stringResource(R.string.on)) else Text(stringResource(R.string.off))
            })
      }

      item {
        ListItem(
            modifier = Modifier.clickable { navToTaildropDirView() },
            leadingContent = {
              Icon(
                  painterResource(R.drawable.baseline_drive_folder_upload_24),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(24.dp),
                  contentDescription = stringResource(R.string.taildrop_dir))
            },
            headlineContent = {
              Text(
                  stringResource(R.string.taildrop_dir_access),
                  style = MaterialTheme.typography.titleMedium)
            },
            supportingContent = {
              val displayPath =
                  permissionsViewModel.currentDir.collectAsState().value?.let {
                    friendlyDirName(it)
                  } ?: "No access"

              Text(displayPath)
            })
      }
    }
  }
}
