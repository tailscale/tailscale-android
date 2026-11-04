// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.components.Header
import com.tailscale.ipn.ui.components.ListItemSwitch
import com.tailscale.ipn.ui.dataSource.DNSSettingsDataSource
import com.tailscale.ipn.ui.model.DnsType
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.AndroidTVUtil
import com.tailscale.ipn.ui.util.ClipboardValueView
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.DNSEnablementState
import com.tailscale.ipn.ui.viewModel.DNSSettingsViewModel
import com.tailscale.ipn.ui.viewModel.DNSSettingsViewModelFactory

data class ViewableRoute(val name: String, val resolvers: List<DnsType.Resolver>)

@Composable
fun DNSSettingsView(
    backToSettings: () -> Unit,
    dataSource: DNSSettingsDataSource,
) {
  val factory = remember(dataSource) { DNSSettingsViewModelFactory(dataSource) }
  val dnsSettingsViewModel: DNSSettingsViewModel = viewModel(factory = factory)
  val screenState by dnsSettingsViewModel.uiState.collectAsStateWithLifecycle()

  val dnsConfig = screenState.dnsConfig
  val resolvers = dnsConfig?.Resolvers.orEmpty()
  val domains = dnsConfig?.Domains.orEmpty()
  val routes =
      dnsConfig?.Routes.orEmpty().mapNotNull { (name, resolvers) ->
        resolvers?.let { ViewableRoute(name = name, resolvers = it) }
      }

  DNSSettingsScreen(
      state =
          DNSSettingsUIState(
              enablement = screenState.enablementState,
              resolvers = resolvers.map { it.Addr.orEmpty() },
              domains = domains,
              routes = routes,
              mdmHiddenFromUser = screenState.mdmHiddenFromUser,
              useCorpDNS = screenState.useCorpDNS,
          ),
      onToggle = { enabled ->
        LoadingIndicator.start()
        dnsSettingsViewModel.setCorpDNS(enabled) { LoadingIndicator.stop() }
      },
      backToSettings = backToSettings,
      isTv = AndroidTVUtil.isAndroidTV(),
  )
}

private data class DNSSettingsUIState(
    val enablement: DNSEnablementState,
    val resolvers: List<String>,
    val domains: List<String>,
    val routes: List<ViewableRoute>,
    val mdmHiddenFromUser: Boolean,
    val useCorpDNS: Boolean,
)

@Composable
private fun DNSSettingsScreen(
    state: DNSSettingsUIState,
    onToggle: (Boolean) -> Unit,
    backToSettings: () -> Unit,
    isTv: Boolean,
) {
  Scaffold(
      topBar = {
        Header(
            R.string.dns_settings,
            onBack = backToSettings,
            isTv = isTv,
        )
      }
  ) { innerPadding ->
    LoadingIndicator.Wrap {
      LazyColumn(Modifier.padding(innerPadding)) {
        item("state") {
          ListItem(
              leadingContent = {
                Icon(
                    painter = painterResource(state.enablement.symbolDrawable),
                    contentDescription = null,
                    tint = state.enablement.tint(),
                    modifier = Modifier.size(36.dp),
                )
              },
              headlineContent = {
                Text(
                    stringResource(state.enablement.title),
                    style = MaterialTheme.typography.titleMedium,
                )
              },
              supportingContent = { Text(stringResource(state.enablement.caption)) },
          )

          if (!state.mdmHiddenFromUser) {
            Lists.ItemDivider()
            ListItemSwitch(
                R.string.use_ts_dns,
                isOn = state.useCorpDNS,
                onToggle = onToggle,
            )
          }
        }

        if (state.resolvers.isNotEmpty()) {
          item("resolversHeader") { Lists.SectionDivider(stringResource(R.string.resolvers)) }

          itemsWithDividers(state.resolvers) { resolver -> ClipboardValueView(resolver) }
        }

        if (state.domains.isNotEmpty()) {
          item("domainsHeader") { Lists.SectionDivider(stringResource(R.string.search_domains)) }

          itemsWithDividers(state.domains) { domain -> ClipboardValueView(domain) }
        }

        if (state.routes.isNotEmpty()) {
          state.routes.forEach { route ->
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

@Preview(showSystemUi = true)
@Composable
fun DNSSettingsPreview() {
  AppTheme {
    DNSSettingsScreen(
        state =
            DNSSettingsUIState(
                enablement = DNSEnablementState.ENABLED,
                resolvers = listOf("1.1.1.1"),
                domains = listOf("tailscale.com"),
                routes = listOf(ViewableRoute(name = "Route", resolvers = emptyList())),
                mdmHiddenFromUser = false,
                useCorpDNS = true,
            ),
        onToggle = {},
        backToSettings = {},
        isTv = false,
    )
  }
}
