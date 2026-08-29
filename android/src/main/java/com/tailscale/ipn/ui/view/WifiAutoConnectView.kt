// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.WifiAutoConnectViewModel

private enum class AddTarget { WHITELIST, BLACKLIST }

@Composable
fun WifiAutoConnectView(
    backToSettings: BackNavigation,
    model: WifiAutoConnectViewModel = viewModel()
) {
  val context = LocalContext.current
  val whitelistSsids by model.whitelistSsids.collectAsState()
  val blacklistSsids by model.blacklistSsids.collectAsState()
  val defaultOn by model.defaultOn.collectAsState()

  var addTarget by remember { mutableStateOf<AddTarget?>(null) }
  var ssidInput by remember { mutableStateOf("") }

  var locationPermissionGranted by remember {
    mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED)
  }
  val permissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationPermissionGranted = granted
      }

  @Suppress("DEPRECATION")
  fun currentSsid(): String? =
      if (locationPermissionGranted)
          (context.getSystemService(android.content.Context.WIFI_SERVICE) as? WifiManager)
              ?.connectionInfo
              ?.ssid
              ?.removePrefix("\"")
              ?.removeSuffix("\"")
              ?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
      else null

  Scaffold(
      topBar = { Header(titleRes = R.string.wifi_auto_connect, onBack = backToSettings) }) {
          innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding)) {

          // Whitelist section
          item("whitelistHeader") {
            Lists.SectionDivider(stringResource(R.string.wifi_whitelist_title))
          }
          item("whitelistDesc") {
            ListItem(headlineContent = { Text(stringResource(R.string.wifi_whitelist_description)) })
          }
          itemsWithDividers(whitelistSsids, key = { "w_$it" }) { ssid ->
            ListItem(
                headlineContent = { Text(ssid) },
                trailingContent = {
                  IconButton(onClick = { model.removeFromWhitelist(ssid) }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.wifi_remove_network))
                  }
                })
          }
          item("addWhitelist") {
            if (whitelistSsids.isNotEmpty()) Lists.ItemDivider()
            ListItem(
                modifier = Modifier.clickable { addTarget = AddTarget.WHITELIST },
                headlineContent = { Text(stringResource(R.string.wifi_add_network)) },
                leadingContent = { Icon(Icons.Default.Add, contentDescription = null) })
          }

          // Blacklist section
          item("blacklistHeader") {
            Lists.SectionDivider(stringResource(R.string.wifi_blacklist_title))
          }
          item("blacklistDesc") {
            ListItem(headlineContent = { Text(stringResource(R.string.wifi_blacklist_description)) })
          }
          itemsWithDividers(blacklistSsids, key = { "b_$it" }) { ssid ->
            ListItem(
                headlineContent = { Text(ssid) },
                trailingContent = {
                  IconButton(onClick = { model.removeFromBlacklist(ssid) }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.wifi_remove_network))
                  }
                })
          }
          item("addBlacklist") {
            if (blacklistSsids.isNotEmpty()) Lists.ItemDivider()
            ListItem(
                modifier = Modifier.clickable { addTarget = AddTarget.BLACKLIST },
                headlineContent = { Text(stringResource(R.string.wifi_add_network)) },
                leadingContent = { Icon(Icons.Default.Add, contentDescription = null) })
          }

          // Default ON toggle
          item("defaultToggle") {
            Lists.SectionDivider(stringResource(R.string.wifi_unknown_networks_title))
            Setting.Switch(
                R.string.wifi_auto_connect_default_on,
                subtitle = stringResource(R.string.wifi_auto_connect_default_on_subtitle),
                isOn = defaultOn,
                onToggle = { model.setDefaultOn(it) })
          }

          // Permission banner
          if (!locationPermissionGranted) {
            item("permissionBanner") {
              ListItem(
                  modifier = Modifier.clickable {
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                  },
                  headlineContent = {
                    Text(stringResource(R.string.wifi_location_permission_rationale))
                  },
                  trailingContent = {
                    TextButton(
                        onClick = {
                          permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }) {
                          Text(stringResource(R.string.wifi_grant_location_permission))
                        }
                  })
            }
          }
        }
      }

  if (addTarget != null) {
    val current = currentSsid()
    AlertDialog(
        onDismissRequest = {
          addTarget = null
          ssidInput = ""
        },
        title = {
          Text(
              stringResource(
                  if (addTarget == AddTarget.WHITELIST) R.string.wifi_whitelist_title
                  else R.string.wifi_blacklist_title))
        },
        text = {
          Column {
            OutlinedTextField(
                value = ssidInput,
                onValueChange = { ssidInput = it },
                placeholder = { Text(stringResource(R.string.wifi_add_network_hint)) },
                singleLine = true)
            if (current != null) {
              TextButton(onClick = { ssidInput = current }) {
                Text(stringResource(R.string.wifi_use_current_network, current))
              }
            }
          }
        },
        confirmButton = {
          TextButton(
              enabled = ssidInput.isNotBlank(),
              onClick = {
                if (addTarget == AddTarget.WHITELIST) model.addToWhitelist(ssidInput)
                else model.addToBlacklist(ssidInput)
                addTarget = null
                ssidInput = ""
              }) {
                Text(stringResource(R.string.wifi_add_confirm))
              }
        },
        dismissButton = {
          TextButton(
              onClick = {
                addTarget = null
                ssidInput = ""
              }) {
                Text(stringResource(R.string.cancel))
              }
        })
  }
}
