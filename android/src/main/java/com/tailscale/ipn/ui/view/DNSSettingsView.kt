// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.DnsType
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.ClipboardValueView
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.DNSEnablementState
import com.tailscale.ipn.ui.viewModel.DNSSettingsViewModel
import com.tailscale.ipn.ui.viewModel.DNSSettingsViewModelFactory

data class ViewableRoute(val name: String, val resolvers: List<DnsType.Resolver>)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DNSSettingsView(
    backToSettings: BackNavigation,
    model: DNSSettingsViewModel = viewModel(factory = DNSSettingsViewModelFactory())
) {
  val state: DNSEnablementState = model.enablementState.collectAsState().value
  val resolvers = model.dnsConfig.collectAsState().value?.Resolvers ?: emptyList()
  val domains = model.dnsConfig.collectAsState().value?.Domains ?: emptyList()
  val routes: List<ViewableRoute> =
      model.dnsConfig.collectAsState().value?.Routes?.mapNotNull { entry ->
        entry.value?.let { resolvers -> ViewableRoute(name = entry.key, resolvers) } ?: run { null }
      } ?: emptyList()
  val useCorpDNS = Notifier.prefs.collectAsState().value?.CorpDNS == true

  Scaffold(topBar = { Header(R.string.dns_settings, onBack = backToSettings) }) { innerPadding ->
    LoadingIndicator.Wrap {
      LazyColumn(Modifier.padding(innerPadding)) {
        item("state") {
          ListItem(
              leadingContent = {
                Icon(
                    painter = painterResource(state.symbolDrawable),
                    contentDescription = null,
                    tint = state.tint(),
                    modifier = Modifier.size(36.dp))
              },
              headlineContent = {
                Text(stringResource(state.title), style = MaterialTheme.typography.titleMedium)
              },
              supportingContent = { Text(stringResource(state.caption)) })

          Lists.ItemDivider()
          Setting.Switch(
              R.string.use_ts_dns,
              isOn = useCorpDNS,
              onToggle = {
                LoadingIndicator.start()
                model.toggleCorpDNS { LoadingIndicator.stop() }
              })
        }

        if (resolvers.isNotEmpty()) {
          item("resolversHeader") { Lists.SectionDivider(stringResource(R.string.resolvers)) }

          itemsWithDividers(resolvers) { resolver -> ClipboardValueView(resolver.Addr.orEmpty()) }
        }

        if (domains.isNotEmpty()) {
          item("domainsHeader") { Lists.SectionDivider(stringResource(R.string.search_domains)) }

          itemsWithDividers(domains) { domain -> ClipboardValueView(domain) }
        }

        if (routes.isNotEmpty()) {
          routes.forEach { route ->
            item { Lists.SectionDivider("Route: ${route.name}") }

            itemsWithDividers(route.resolvers) { resolver ->
              ClipboardValueView(resolver.Addr.orEmpty())
            }
          }
        }
      }
    }
  }
}

@Preview
@Composable
fun DNSSettingsViewPreview() {
  val vm = DNSSettingsViewModel()
  vm.enablementState.set(DNSEnablementState.ENABLED)
  DNSSettingsView(backToSettings = { }, vm)
}
