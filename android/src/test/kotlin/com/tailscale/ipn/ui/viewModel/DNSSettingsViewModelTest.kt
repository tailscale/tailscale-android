// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.mdm.AlwaysNeverUserDecides
import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.ui.dataSource.DNSSettingsDataSource
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.SelfNodeUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DNSSettingsViewModelTest {
  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun profileSwitchIgnoresPreviousProfileResponse() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))

    val source = FakeDNSSettingsDataSource()
    val vm = DNSSettingsViewModel(source)

    // uiState uses WhileSubscribed, so keep a collector active.
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    try {
      runCurrent()

      // Display profile A's initial config.
      val configA = Tailcfg.DNSConfig(Domains = listOf("a.example"))
      source.requests.removeFirst()(Result.success(configA))
      runCurrent()
      assertEquals(configA, vm.uiState.value.dnsConfig)

      // Start another fetch for A, but delay its response.
      vm.refreshDNSConfig()
      val delayedResponseA = source.requests.removeFirst()

      // Switching to B must immediately clear A's config.
      source.changeProfile("B")
      runCurrent()
      assertNull(vm.uiState.value.dnsConfig)

      // A's late response must not restore the old config.
      delayedResponseA(Result.success(configA))
      runCurrent()
      assertNull(vm.uiState.value.dnsConfig)

      // B's response should populate the screen.
      val configB = Tailcfg.DNSConfig(Domains = listOf("b.example"))
      source.requests.removeFirst()(Result.success(configB))
      runCurrent()
      assertEquals(configB, vm.uiState.value.dnsConfig)
    } finally {
      vm.viewModelScope.cancel()
    }
  }

  @Test
  fun backendStopClearsConfigAndIgnoresPendingResponse() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))

    val source = FakeDNSSettingsDataSource()
    val vm = DNSSettingsViewModel(source)

    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    try {
      runCurrent()

      val config = Tailcfg.DNSConfig(Domains = listOf("a.example"))
      source.requests.removeFirst()(Result.success(config))
      runCurrent()
      assertEquals(config, vm.uiState.value.dnsConfig)

      // Start a refresh, then stop before its response arrives.
      vm.refreshDNSConfig()
      val delayedResponse = source.requests.removeFirst()

      source.state.value = Ipn.State.Stopped
      runCurrent()
      assertNull(vm.uiState.value.dnsConfig)

      delayedResponse(Result.success(config))
      runCurrent()
      assertNull(vm.uiState.value.dnsConfig)

      // Opening the screen while stopped must not start another fetch.
      vm.refreshDNSConfig()
      assertEquals(0, source.requests.size)
    } finally {
      vm.viewModelScope.cancel()
    }
  }

  @Test
  fun selfChangeRefreshesConfigWhenNodeFieldsAreUnchanged() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))

    val source = FakeDNSSettingsDataSource()
    val vm = DNSSettingsViewModel(source)

    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    try {
      runCurrent()

      val initialConfig = Tailcfg.DNSConfig(Domains = listOf("initial.example"))
      source.requests.removeFirst()(Result.success(initialConfig))
      runCurrent()
      assertEquals(initialConfig, vm.uiState.value.dnsConfig)

      val stableID = requireNotNull(source.netmap.value).SelfNode.StableID

      for (revision in 1L..2L) {
        source.publishSelfNodeUpdate(stableID)
        runCurrent()

        val updatedConfig = Tailcfg.DNSConfig(Domains = listOf("update-$revision.example"))
        source.requests.removeFirst()(Result.success(updatedConfig))
        runCurrent()
        assertEquals(updatedConfig, vm.uiState.value.dnsConfig)
      }
    } finally {
      vm.viewModelScope.cancel()
    }
  }

  @Test
  fun missingSelfNodeClearsConfigAndIgnoresPendingResponse() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))

    val source = FakeDNSSettingsDataSource()
    val vm = DNSSettingsViewModel(source)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    try {
      runCurrent()

      val config = Tailcfg.DNSConfig(Domains = listOf("a.example"))
      source.requests.removeFirst()(Result.success(config))
      runCurrent()
      assertEquals(config, vm.uiState.value.dnsConfig)

      vm.refreshDNSConfig()
      val delayedResponse = source.requests.removeFirst()

      source.netmap.value = null
      source.publishSelfNodeUpdate(null)
      runCurrent()
      assertNull(vm.uiState.value.dnsConfig)

      delayedResponse(Result.success(config))
      runCurrent()
      assertNull(vm.uiState.value.dnsConfig)

      vm.refreshDNSConfig()
      assertEquals(0, source.requests.size)
    } finally {
      vm.viewModelScope.cancel()
    }
  }

  private class FakeDNSSettingsDataSource : DNSSettingsDataSource {
    override val state = MutableStateFlow(Ipn.State.Running)

    override val prefs = MutableStateFlow<Ipn.Prefs?>(Ipn.Prefs(CorpDNS = true))

    override val netmap =
        MutableStateFlow<Netmap.NetworkMap?>(
            Netmap.NetworkMap(
                SelfNode = Tailcfg.Node(StableID = "A"),
                Domain = "a.example",
                UserProfiles = emptyMap(),
                TKAEnabled = false,
            )
        )

    override val dnsSettingsPolicy =
        MutableStateFlow(
            SettingState(
                value = AlwaysNeverUserDecides.UserDecides,
                isSet = false,
            )
        )

    override val selfNodeUpdate = MutableStateFlow<SelfNodeUpdate?>(null)

    val requests = ArrayDeque<(Result<Tailcfg.DNSConfig>) -> Unit>()

    override fun dnsConfig(
        scope: CoroutineScope,
        callback: (Result<Tailcfg.DNSConfig>) -> Unit,
    ) {
      requests.addLast(callback)
    }

    override fun editPrefs(
        scope: CoroutineScope,
        prefs: Ipn.MaskedPrefs,
        callback: (Result<Ipn.Prefs>) -> Unit,
    ) {
      error("Unexpected editPrefs call")
    }

    fun changeProfile(nodeID: String) {
      val node = Tailcfg.Node(StableID = nodeID)
      netmap.value = requireNotNull(netmap.value).copy(SelfNode = node)
      publishSelfNodeUpdate(node.StableID)
    }

    fun publishSelfNodeUpdate(stableID: String?) {
      selfNodeUpdate.value =
          SelfNodeUpdate(
              revision = (selfNodeUpdate.value?.revision ?: 0L) + 1,
              stableID = stableID,
          )
    }
  }
}
