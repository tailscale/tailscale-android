// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Health
import com.tailscale.ipn.ui.theme.success
import com.tailscale.ipn.ui.util.AndroidTVUtil.isAndroidTV
import com.tailscale.ipn.ui.viewModel.HealthViewModel

@Composable
fun HealthView(backToSettings: BackNavigation, model: HealthViewModel = viewModel()) {
  val warnings by model.warnings.collectAsState()

  Scaffold(topBar = { Header(titleRes = R.string.health_warnings, onBack = backToSettings) }) {
      innerPadding ->
    LazyColumn(modifier = Modifier.padding(innerPadding)) {
      if (warnings.isEmpty()) {
        item("allGood") {
          Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(16.dp, alignment = Alignment.Top),
              modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.check_circle),
                    modifier = Modifier.size(48.dp),
                    contentDescription = "A green checkmark",
                    tint = MaterialTheme.colorScheme.success)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement =
                        Arrangement.spacedBy(2.dp, alignment = Alignment.CenterVertically),
                    modifier = Modifier.fillMaxWidth()) {
                      Text(
                          text = stringResource(R.string.no_issues_found),
                          fontSize = MaterialTheme.typography.titleMedium.fontSize,
                          fontWeight = MaterialTheme.typography.titleMedium.fontWeight)
                      Text(
                          text = stringResource(R.string.tailscale_is_operating_normally),
                          color = MaterialTheme.colorScheme.secondary)
                    }
              }
        }
      }

      items(warnings) { HealthWarningView(it) }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HealthWarningView(warning: Health.UnhealthyState) {
  val localClipboardManager = LocalClipboardManager.current
  val context = LocalContext.current
  val copiedText = stringResource(R.string.copied)
  var menuExpanded by remember { mutableStateOf(false) }

  // Android TV has no clipboard.
  val itemModifier =
      if (isAndroidTV()) {
        Modifier
      } else {
        Modifier.combinedClickable(onClick = {}, onLongClick = { menuExpanded = true })
      }

  Box(modifier = Modifier.background(color = MaterialTheme.colorScheme.surfaceContainerLow)) {
    Box(
        modifier =
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                .clip(shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 10.dp))
                .fillMaxWidth()) {
          ListItem(
              modifier = itemModifier,
              colors = warning.Severity.listItemColors(),
              headlineContent = {
                if (warning.Title.isNotEmpty()) {
                  Text(
                      warning.Title,
                      style = MaterialTheme.typography.titleMedium,
                  )
                }
              },
              supportingContent = {
                Text(warning.Text, style = MaterialTheme.typography.bodyMedium)
              })

          // Copy for now; KB-page links to follow.
          DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                leadingIcon = {
                  Icon(painter = painterResource(R.drawable.clipboard), contentDescription = null)
                },
                text = { Text(text = stringResource(R.string.copy)) },
                onClick = {
                  localClipboardManager.setText(AnnotatedString(warning.clipboardText))
                  // Android 13+ shows its own copy confirmation.
                  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                  }
                  menuExpanded = false
                })
          }
        }
  }
}
