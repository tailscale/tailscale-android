// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.SplitTunnelAppPickerViewModel

@Composable
fun SplitTunnelAppPickerView(
    backToSettings: BackNavigation,
    model: SplitTunnelAppPickerViewModel = viewModel(),
) {
  val installedApps by model.installedApps.collectAsState()
  val selectedPackageNames by model.selectedPackageNames.collectAsState()
  val allowSelected by model.allowSelected.collectAsState()
  val builtInDisallowedPackageNames: List<String> = App.get().builtInDisallowedPackageNames
  val mdmIncludedPackages by model.mdmIncludedPackages.collectAsState()
  val mdmExcludedPackages by model.mdmExcludedPackages.collectAsState()
  val showHeaderMenu by model.showHeaderMenu.collectAsState()
  val showSwitchDialog by model.showSwitchDialog.collectAsState()

  if (showSwitchDialog) {
    SwitchAlertDialog(
        allowSelected = allowSelected,
        onConfirm = {
          model.showSwitchDialog.set(false)
          model.performSelectionSwitch()
        },
        onDismiss = { model.showSwitchDialog.set(false) },
    )
  }

  Scaffold(
      topBar = {
        Header(
            titleRes = R.string.split_tunneling,
            onBack = backToSettings,
            actions = {
              Row {
                FusMenu(viewModel = model, onSwitchClick = { model.showSwitchDialog.set(true) })
                IconButton(onClick = { model.showHeaderMenu.set(!showHeaderMenu) }) {
                  Icon(Icons.Default.MoreVert, "menu")
                }
              }
            },
        )
      },
  ) { innerPadding ->
    LazyColumn(modifier = Modifier.padding(innerPadding)) {
      if (mdmExcludedPackages.value?.isNotEmpty() == true) {
        item("mdmExcludedNotice") {
          ListItem(
              headlineContent = {
                Text(stringResource(R.string.certain_apps_are_not_routed_via_tailscale))
              }
          )
        }
      } else if (mdmIncludedPackages.value?.isNotEmpty() == true) {
        item("mdmIncludedNotice") {
          ListItem(
              headlineContent = {
                Text(stringResource(R.string.only_specific_apps_are_routed_via_tailscale))
              }
          )
        }
      } else {
        item("header") {
          ListItem(
              headlineContent = {
                Text(
                    stringResource(
                        if (allowSelected) R.string.selected_apps_will_access_tailscale
                        else
                            R.string
                                .selected_apps_will_access_the_internet_directly_without_using_tailscale
                    )
                )
              }
          )
        }
        item("resolversHeader") {
          Lists.SectionDivider(
              stringResource(
                  if (allowSelected) R.string.count_included_apps else R.string.count_excluded_apps,
                  selectedPackageNames.count(),
              )
          )
        }
        if (installedApps.isEmpty()) {
          item("spinner") {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator(
                  modifier = Modifier.width(64.dp),
                  color = MaterialTheme.colorScheme.secondary,
                  trackColor = MaterialTheme.colorScheme.surfaceVariant,
              )
            }
          }
        } else {
          items(installedApps, key = { it.packageName }) { app ->
            ListItem(
                headlineContent = { Text(app.name, fontWeight = FontWeight.SemiBold) },
                leadingContent = {
                  Image(
                      bitmap =
                          model.installedAppsManager.packageManager
                              .getApplicationIcon(app.packageName)
                              .toBitmap()
                              .asImageBitmap(),
                      contentDescription = null,
                      modifier = Modifier.width(40.dp).height(40.dp),
                  )
                },
                supportingContent = {
                  Text(
                      app.packageName,
                      color = MaterialTheme.colorScheme.secondary,
                      fontSize = MaterialTheme.typography.bodySmall.fontSize,
                      letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing,
                  )
                },
                trailingContent = {
                  Checkbox(
                      checked = selectedPackageNames.contains(app.packageName),
                      enabled = !builtInDisallowedPackageNames.contains(app.packageName),
                      onCheckedChange = { checked ->
                        if (checked) {
                          model.select(packageName = app.packageName)
                        } else {
                          model.deselect(packageName = app.packageName)
                        }
                      },
                  )
                },
            )
            Lists.ItemDivider()
          }
        }
      }
    }
  }
}

@Composable
fun FusMenu(viewModel: SplitTunnelAppPickerViewModel, onSwitchClick: (() -> Unit)) {
  val expanded by viewModel.showHeaderMenu.collectAsState()
  val allowSelected by viewModel.allowSelected.collectAsState()

  DropdownMenu(
      expanded = expanded,
      onDismissRequest = { viewModel.showHeaderMenu.set(false) },
      modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
  ) {
    MenuItem(
        onClick = {
          viewModel.showHeaderMenu.set(false)
          onSwitchClick()
        },
        text =
            stringResource(
                if (allowSelected) R.string.switch_to_select_to_exclude
                else R.string.switch_to_select_to_include
            ),
    )
  }
}

@Composable
fun SwitchAlertDialog(allowSelected: Boolean, onConfirm: (() -> Unit), onDismiss: (() -> Unit)) {
  val switchString =
      stringResource(
          if (allowSelected) R.string.switch_to_select_to_exclude
          else R.string.switch_to_select_to_include
      )
  val switchDescription =
      stringResource(
          if (allowSelected)
              R.string.selected_apps_will_access_the_internet_directly_without_using_tailscale
          else R.string.selected_apps_will_access_tailscale
      )

  AlertDialog(
      title = { Text(text = "$switchString?") },
      text = {
        Text(
            text =
                stringResource(R.string.your_current_selection_will_be_cleared) +
                    "\n$switchDescription"
        )
      },
      onDismissRequest = onDismiss,
      confirmButton = { TextButton(onClick = onConfirm) { Text(text = switchString) } },
      dismissButton = {
        TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.cancel)) }
      },
  )
}
