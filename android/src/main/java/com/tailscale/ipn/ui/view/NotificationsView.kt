// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Permissions
import com.tailscale.ipn.ui.theme.exitNodeToggleButton

@Composable
fun NotificationsView(backToPermissionsView: BackNavigation, openApplicationSettings: () -> Unit) {
  val permissions = Permissions.withGrantedStatus

  // Find the notification permission
  val notificationPermission =
      permissions.find { (permission, _) ->
        permission.title == R.string.permission_post_notifications
      }
  val granted = notificationPermission?.second ?: false
  val permission = notificationPermission?.first

  Scaffold(
      topBar = {
        Header(titleRes = R.string.permission_post_notifications, onBack = backToPermissionsView)
      }) { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding)) {
          item {
            if (permission != null) {
              ListItem(
                  headlineContent = {
                    Text(
                        stringResource(permission.title),
                        style = MaterialTheme.typography.titleMedium)
                  },
                  supportingContent = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                      Text(
                          text = stringResource(permission.description),
                          style = MaterialTheme.typography.bodyMedium)
                      Spacer(modifier = Modifier.height(12.dp))
                      Text(
                          text = stringResource(R.string.notification_settings_explanation),
                          style = MaterialTheme.typography.bodyMedium)
                    }
                  })
            }
          }

          item("spacer") {
            Spacer(modifier = Modifier.height(16.dp)) // soft break instead of divider
          }

          item {
            ListItem(
                headlineContent = {
                  Text(
                      text = stringResource(R.string.permission_post_notifications),
                      style = MaterialTheme.typography.titleMedium)
                },
                supportingContent = {
                  Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text =
                            if (granted) stringResource(R.string.on)
                            else stringResource(R.string.off),
                        style = MaterialTheme.typography.bodyMedium)
                    Button(
                        colors = MaterialTheme.colorScheme.exitNodeToggleButton,
                        onClick = openApplicationSettings,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                          Text(stringResource(R.string.open_notification_settings))
                        }
                  }
                })
          }
        }
      }
}
