// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.viewModel.TrustedNetworksViewModel

@Composable
fun TrustedNetworksView(
    onNavigateBack: () -> Unit,
    viewModel: TrustedNetworksViewModel = viewModel()
) {
  val enabled by viewModel.enabled.collectAsState()
  val ssids by viewModel.trustedSsids.collectAsState()
  val currentSsid by viewModel.currentSsid.collectAsState()
  var manualInput by remember { mutableStateOf("") }

  val context = LocalContext.current
  var locationGranted by remember {
    mutableStateOf(
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
  }
  val permissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationGranted = granted
        if (granted) viewModel.refreshCurrentSsid()
      }

  LaunchedEffect(enabled) {
    if (enabled && !locationGranted) {
      permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }
  }

  Scaffold(topBar = { Header(titleRes = R.string.auto_vpn, onBack = onNavigateBack) }) {
      innerPadding ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())) {
          Text(
              text =
                  "When enabled, Tailscale VPN will be disabled on trusted Wi-Fi networks " +
                      "and enabled automatically on untrusted Wi-Fi, cellular data, " +
                      "or when no Wi-Fi is connected.",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(bottom = 16.dp))

          // -- Feature toggle --
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically) {
                Text("Enable Auto-VPN", style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, onCheckedChange = { viewModel.setEnabled(it) })
              }

          Spacer(modifier = Modifier.height(24.dp))

          if (!enabled) {
            Text(
                text = "Enable Auto-VPN above to configure trusted networks.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
          } else {
            if (!locationGranted) {
              Text(
                  text =
                      "Location permission is required to detect the current Wi-Fi network. " +
                          "Without it, VPN will stay enabled on all networks (fail-secure).",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.error,
                  modifier = Modifier.padding(bottom = 8.dp))
              OutlinedButton(
                  onClick = {
                    permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                  },
                  modifier = Modifier.fillMaxWidth()) {
                    Text("Grant location permission")
                  }
              Spacer(modifier = Modifier.height(16.dp))
            }

            // -- Current network shortcut --
            currentSsid?.let { ssid ->
              if (ssid !in ssids) {
                OutlinedButton(
                    onClick = { viewModel.addSsid(ssid) }, modifier = Modifier.fillMaxWidth()) {
                      Icon(Icons.Default.Add, contentDescription = null)
                      Spacer(modifier = Modifier.width(8.dp))
                      Text("Trust current network: $ssid")
                    }
              } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(
                      Icons.Default.CheckCircle,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary)
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                      text = "Current network is trusted: $ssid",
                      color = MaterialTheme.colorScheme.primary)
                }
              }
              Spacer(modifier = Modifier.height(16.dp))
            }

            // -- Manual SSID entry --
            Text(
                text = "Add a trusted network manually",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
              OutlinedTextField(
                  value = manualInput,
                  onValueChange = { manualInput = it },
                  placeholder = { Text("Network SSID") },
                  singleLine = true,
                  modifier = Modifier.weight(1f))
              Spacer(modifier = Modifier.width(8.dp))
              IconButton(
                  onClick = {
                    val trimmed = manualInput.trim()
                    if (trimmed.isNotBlank()) {
                      viewModel.addSsid(trimmed)
                      manualInput = ""
                    }
                  }) {
                    Icon(Icons.Default.Add, contentDescription = "Add network")
                  }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // -- Trusted network list --
            Text(
                text = "Trusted Networks",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp))

            if (ssids.isEmpty()) {
              Text(
                  text = "No trusted networks yet. Add your home network above.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
              ssids.sorted().forEach { ssid ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                      Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(ssid)
                      }
                      IconButton(onClick = { viewModel.removeSsid(ssid) }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove $ssid",
                            tint = MaterialTheme.colorScheme.error)
                      }
                    }
                HorizontalDivider()
              }
            }
          }
        }
  }
}
