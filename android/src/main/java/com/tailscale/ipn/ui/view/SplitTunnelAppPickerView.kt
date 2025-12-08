// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.UninitializedApp.SplitTunnelMode
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.viewModel.SplitTunnelAppPickerViewModel

@Composable
fun SplitTunnelAppPickerView(
    backToSettings: BackNavigation,
    model: SplitTunnelAppPickerViewModel = viewModel()
) {
  val installedApps by model.installedApps.collectAsState()
  val excludedPackageNames by model.excludedPackageNames.collectAsState()
    val includedPackageNames by model.includedPackageNames.collectAsState()


    val builtInDisallowedPackageNames: List<String> = App.get().builtInDisallowedPackageNames
  val mdmIncludedPackages by model.mdmIncludedPackages.collectAsState()
  val mdmExcludedPackages by model.mdmExcludedPackages.collectAsState()

    val splitEnabled = remember { mutableStateOf(App.get().isSplitTunnelEnabled())}
    val currentSplitMode = remember { mutableStateOf(App.get().getSplitTunnelMode())}
    val searchQuery = remember { mutableStateOf("") }

    val filteredApps = installedApps.filter { app ->
        searchQuery.value.isBlank() ||
                app.name.contains(searchQuery.value, ignoreCase = true) ||
                app.packageName.contains(searchQuery.value, ignoreCase = true)
    }




    Scaffold(topBar = { Header(titleRes = R.string.split_tunneling, onBack = backToSettings) }) {
      innerPadding ->
    LazyColumn(modifier = Modifier.padding(innerPadding)) {
      item(key = "header") {

          val mdmActive =
              (mdmExcludedPackages.value?.isNotEmpty() == true) ||
                      (mdmIncludedPackages.value?.isNotEmpty() == true)


          if (mdmActive) {
              Setting.Switch(
                  R.string.split_tunneling_enabled,
                  isOn = true,
                  enabled = false,
                  onToggle = {}
              )
          } else {

              Setting.Switch(
                  R.string.split_tunneling_enabled,
                  isOn = splitEnabled.value,
                  onToggle = {
                      val newVal = !App.get().isSplitTunnelEnabled()
                      App.get().setSplitTunnelEnabled(newVal)
                      splitEnabled.value = App.get().isSplitTunnelEnabled()
                  }
              )
          }

          ListItem(
            headlineContent = {
              Text(
                  stringResource(
                      R.string
                          .selected_apps_will_follow_custom_routing))
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
            if (splitEnabled.value) {

                item("resolversHeader") {

                    Spacer(modifier = Modifier.height(8.dp))


                    AppSearchBar(
                        query = searchQuery.value,
                        onQueryChange = { searchQuery.value = it }
                    )

                    Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                        FilterChip(
                            selected = currentSplitMode.value == SplitTunnelMode.EXCLUDE,
                            onClick = {
                                App.get().setSplitTunnelMode(SplitTunnelMode.EXCLUDE)
                                currentSplitMode.value = App.get().getSplitTunnelMode()
                            },
                            label = { Text("Exclude apps") }
                        )

                        Spacer(modifier = Modifier.width(8.dp))


                        FilterChip(
                            selected = currentSplitMode.value == SplitTunnelMode.INCLUDE,
                            onClick = {
                                App.get().setSplitTunnelMode(SplitTunnelMode.INCLUDE)
                                currentSplitMode.value = App.get().getSplitTunnelMode()
                            },
                            label = { Text("Include apps") }
                        )
                    }
                }



                if (currentSplitMode.value == SplitTunnelMode.EXCLUDE) {
                    item("resolversHeaderExclude") {
                        Lists.SectionDivider(
                            stringResource(
                                R.string.count_excluded_apps,
                                excludedPackageNames.count()
                            )
                        )
                    }

                    if (filteredApps.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(vertical = 24.dp, horizontal = 16.dp)
                            ) {
                                Text(
                                    "No apps found",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    items(filteredApps) { app ->
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
                                    modifier = Modifier.width(40.dp).height(40.dp)
                                )
                            },
                            supportingContent = {
                                Text(
                                    app.packageName,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                    letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing
                                )
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
                } else {

                    item("resolversHeaderInclude") {
                        Lists.SectionDivider(
                            stringResource(
                                R.string.count_included_apps,
                                includedPackageNames.count()
                            )
                        )
                    }

                    if (filteredApps.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(vertical = 24.dp, horizontal = 16.dp)
                            ) {
                                Text(
                                    "No apps found",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }


                    items(filteredApps) { app ->
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
                                    modifier = Modifier.width(40.dp).height(40.dp)
                                )
                            },
                            supportingContent = {
                                Text(
                                    app.packageName,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                    letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing
                                )
                            },
                            trailingContent = {
                                Checkbox(
                                    checked = includedPackageNames.contains(app.packageName),
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            model.include(packageName = app.packageName)
                                        } else {
                                            model.uninclude(packageName = app.packageName)
                                        }
                                    })
                            })
                        Lists.ItemDivider()


                    }



                }
            }
        }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSearchBar(
    query: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer),
        value = query,
        onValueChange = onQueryChange,
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search"
            )
        },
        shape = SearchBarDefaults.dockedShape,
        placeholder = { Text(stringResource(R.string.search_apps_ellipsis)) },
        singleLine = true,
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        }
    )
}


