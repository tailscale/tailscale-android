// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.DnsType
import com.tailscale.ipn.ui.util.ClipboardValueView
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.viewModel.DNSEnablementState
import com.tailscale.ipn.ui.viewModel.DNSSettingsViewModel
import com.tailscale.ipn.ui.viewModel.DNSSettingsViewModelFactory

data class ViewableRoute(val name: String, val resolvers: List<DnsType.Resolver>)

@Composable
fun DNSSettingsView(
    nav: BackNavigation,
    model: DNSSettingsViewModel = viewModel(factory = DNSSettingsViewModelFactory())
) {
  val state: DNSEnablementState = model.enablementState.collectAsState().value
  val resolvers = model.dnsConfig.collectAsState().value?.Resolvers ?: emptyList()
  val domains = model.dnsConfig.collectAsState().value?.Domains ?: emptyList()
  val routes: List<ViewableRoute> =
      model.dnsConfig.collectAsState().value?.Routes?.mapNotNull { entry ->
        entry.value?.let { resolvers -> ViewableRoute(name = entry.key, resolvers) } ?: run { null }
      } ?: emptyList()

  Scaffold(topBar = { Header(R.string.dns_settings, onBack = nav.onBack) }) { innerPadding ->
    LoadingIndicator.Wrap {
      LazyColumn(Modifier.padding(innerPadding).padding(16.dp)) {
        item("state") { FeatureStateView(state) }

        item("toggle") { SettingRow(model.useDNSSetting) }

        if (resolvers.isNotEmpty()) {
          item("resolversHeader") {
            Text(stringResource(R.string.resolvers), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
          }

          items(resolvers) { resolver ->
            ClipboardValueView(resolver.Addr.orEmpty())
            Spacer(Modifier.size(8.dp))
          }
        }

        if (domains.isNotEmpty()) {
          item("domainsHeader") {
            Text(stringResource(R.string.search_domains), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
          }
          items(domains) { domain ->
            ClipboardValueView(domain)
            Spacer(Modifier.size(8.dp))
          }
        }

        if (routes.isNotEmpty()) {
          item("routesHeader") { Spacer(Modifier.size(8.dp)) }
          items(routes) { route ->
            Text("Route: ${route.name}", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              route.resolvers.forEach { ClipboardValueView(it.Addr.orEmpty()) }
            }
            Spacer(Modifier.size(16.dp))
          }
        }
      }
    }
  }
}
