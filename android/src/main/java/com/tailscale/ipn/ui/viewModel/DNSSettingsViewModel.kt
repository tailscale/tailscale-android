// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.theme.off
import com.tailscale.ipn.ui.theme.success
import com.tailscale.ipn.ui.util.FeatureStateRepresentation
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DNSSettingsViewModelFactory() : ViewModelProvider.Factory {
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return DNSSettingsViewModel() as T
  }
}

class DNSSettingsViewModel() : IpnViewModel() {
  val enablementState: StateFlow<DNSEnablementState> =
      MutableStateFlow(DNSEnablementState.NOT_RUNNING)
  val dnsConfig: StateFlow<Tailcfg.DNSConfig?> = MutableStateFlow(null)

  val useDNSSetting =
      Setting(
          R.string.use_ts_dns,
          type = SettingType.SWITCH,
          isOn = MutableStateFlow(Notifier.prefs.value?.CorpDNS),
          onToggle = { toggleCorpDNS {} })

  init {
    viewModelScope.launch {
      Notifier.netmap
          .combine(Notifier.prefs) { netmap, prefs -> Pair(netmap, prefs) }
          .stateIn(viewModelScope)
          .collect { (netmap, prefs) ->
            Log.d("DNSSettingsViewModel", "prefs: CorpDNS=" + prefs?.CorpDNS.toString())
            prefs?.let {
              useDNSSetting.isOn?.set(it.CorpDNS)
              useDNSSetting.enabled.set(true)

              if (it.CorpDNS) {
                enablementState.set(DNSEnablementState.ENABLED)
              } else {
                enablementState.set(DNSEnablementState.DISABLED)
              }
            }
                ?: run {
                  enablementState.set(DNSEnablementState.NOT_RUNNING)
                  useDNSSetting.enabled.set(false)
                }
            netmap?.let { dnsConfig.set(netmap.DNS) }
          }
    }
  }

  fun toggleCorpDNS(callback: (Result<Ipn.Prefs>) -> Unit) {
    val prefs =
        Notifier.prefs.value
            ?: run {
              callback(Result.failure(Exception("no prefs")))
              return@toggleCorpDNS
            }

    val prefsOut = Ipn.MaskedPrefs()
    prefsOut.CorpDNS = !prefs.CorpDNS
    Client(viewModelScope).editPrefs(prefsOut, callback)
  }
}

enum class DNSEnablementState : FeatureStateRepresentation {
  NOT_RUNNING {
    override val title: Int
      @StringRes get() = R.string.not_running

    override val caption: Int
      @StringRes
      get() = R.string.tailscale_is_not_running_this_device_is_using_the_system_dns_resolver

    override val tint: Color
      @Composable get() = MaterialTheme.colorScheme.off

    override val symbolDrawable: Int
      get() = R.drawable.xmark_circle
  },
  ENABLED {
    override val title: Int
      @StringRes get() = R.string.using_tailscale_dns

    override val caption: Int
      @StringRes get() = R.string.this_device_is_using_tailscale_to_resolve_dns_names

    override val tint: Color
      @Composable get() = MaterialTheme.colorScheme.success

    override val symbolDrawable: Int
      get() = R.drawable.check_circle
  },
  DISABLED {
    override val title: Int
      @StringRes get() = R.string.not_using_tailscale_dns

    override val caption: Int
      @StringRes get() = R.string.this_device_is_using_the_system_dns_resolver

    override val tint: Color
      @Composable get() = MaterialTheme.colorScheme.error

    override val symbolDrawable: Int
      get() = R.drawable.xmark_circle
  }
}
