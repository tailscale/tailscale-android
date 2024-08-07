// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.tailscale.ipn.ui.viewModel.SplitTunnelAppPickerViewModel

@Composable
fun SplitTunnelAppPickerView(
    backToSettings: BackNavigation,
    model: SplitTunnelAppPickerViewModel = viewModel()
) {
  val installedApps by model.installedApps.collectAsState()
  val excludedPackageNames by model.excludedPackageNames.collectAsState()
  val builtInDisallowedPackageNames: List<String> = App.get().builtInDisallowedPackageNames
  val mdmIncludedPackages by model.mdmIncludedPackages.collectAsState()
  val mdmExcludedPackages by model.mdmExcludedPackages.collectAsState()

  Scaffold(topBar = { Header(titleRes = R.string.split_tunneling, onBack = backToSettings) }) {
      innerPadding ->
    LazyColumn(modifier = Modifier.padding(innerPadding)) {
      item(key = "header") {
        ListItem(
            headlineContent = {
              Text(
                  stringResource(
                      R.string
                          .selected_apps_will_access_the_internet_directly_without_using_tailscale))
            })
      }
      if (mdmExcludedPackages.value?.isNotEmpty() == true) {
        item("mdmExcludedNotice") {
          ListItem(
              headlineContent = {
                Text(stringResource(R.string.certain_apps_are_not_routed_via_tailscale))
              })
        }
      } else if (mdmIncludedPackages.value?.isNotEmpty() == true) {
        item("mdmIncludedNotice") {
          ListItem(
              headlineContent = {
                Text(stringResource(R.string.only_specific_apps_are_routed_via_tailscale))
              })
        }
      } else {
        item("resolversHeader") {
          Lists.SectionDivider(
              stringResource(R.string.count_excluded_apps, excludedPackageNames.count()))
        }
        items(installedApps) { app ->
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
                    modifier = Modifier.width(40.dp).height(40.dp))
              },
              supportingContent = {
                Text(
                    app.packageName,
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing)
              },
              trailingContent = {
                Checkbox(
                    checked = excludedPackageNames.contains(app.packageName),
                    enabled = !builtInDisallowedPackageNames.contains(app.packageName),
                    onCheckedChange = { checked ->
                      if (checked) {
                        model.exclude(packageName = app.packageName)
                      } else {
                        model.unexclude(packageName = app.packageName)
                      }
                    })
              })
          Lists.ItemDivider()
        }
      }
    }
  }
}
