// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.mdm.AlwaysNeverUserDecides
import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.ui.dataSource.DNSSettingsDataSource
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.SelfNodeUpdate
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing

@OptIn(ExperimentalCoroutinesApi::class)
class DNSSettingsViewModelTest {
  private lateinit var originalWrapper: TSLog.LibtailscaleWrapper

  @Before
  fun setUp() {
    originalWrapper = TSLog.libtailscaleWrapper
    val logMock = mock(TSLog.LibtailscaleWrapper::class.java)
    doNothing().`when`(logMock).sendLog(any(), any())
    TSLog.libtailscaleWrapper = logMock
  }

  @After
  fun tearDown() {
    TSLog.libtailscaleWrapper = originalWrapper
    Dispatchers.resetMain()
  }

  private fun TestScope.startWith(
      config: Tailcfg.DNSConfig
  ): Pair<FakeDNSSettingsDataSource, DNSSettingsViewModel> {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val source = FakeDNSSettingsDataSource()
    val vm = DNSSettingsViewModel(source)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    runCurrent()
    source.requests.removeFirst()(Result.success(config))
    runCurrent()
    assertEquals(config, vm.uiState.value.dnsConfig)
    return source to vm
  }

  @Test
  fun openingScreenFetchesOnce() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val source = FakeDNSSettingsDataSource()
    val vm = DNSSettingsViewModel(source)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    try {
      runCurrent()
      assertEquals(1, source.requests.size)
    } finally {
      vm.viewModelScope.cancel()
    }
  }

  @Test
  fun profileSwitchIgnoresPreviousProfileResponse() = runTest {
    val configA = Tailcfg.DNSConfig(Domains = listOf("a.example"))
    val (source, vm) = startWith(configA)

    try {
      source.publishSelfNodeUpdate("A")
      runCurrent()
      val delayedResponseA = source.requests.removeFirst()

      source.publishSelfNodeUpdate("B")
      runCurrent()
      assertNull(vm.uiState.value.dnsConfig)

      delayedResponseA(Result.success(configA))
      runCurrent()
      assertNull(vm.uiState.value.dnsConfig)

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
    val config = Tailcfg.DNSConfig(Domains = listOf("a.example"))
    val (source, vm) = startWith(config)

    try {
      source.publishSelfNodeUpdate("A")
      runCurrent()
      val delayedResponse = source.requests.removeFirst()

      source.state.value = Ipn.State.Stopped
      runCurrent()
      assertNull(vm.uiState.value.dnsConfig)

      delayedResponse(Result.success(config))
      runCurrent()
      assertNull(vm.uiState.value.dnsConfig)

      source.publishSelfNodeUpdate("A")
      runCurrent()
      assertEquals(0, source.requests.size)
    } finally {
      vm.viewModelScope.cancel()
    }
  }

  @Test
  fun selfChangeRefreshesConfigForSameNode() = runTest {
    val (source, vm) = startWith(Tailcfg.DNSConfig(Domains = listOf("initial.example")))
    try {
      for (revision in 1L..2L) {
        source.publishSelfNodeUpdate("A")
        runCurrent()
        val updated = Tailcfg.DNSConfig(Domains = listOf("update-$revision.example"))
        source.requests.removeFirst()(Result.success(updated))
        runCurrent()
        assertEquals(updated, vm.uiState.value.dnsConfig)
      }
    } finally {
      vm.viewModelScope.cancel()
    }
  }

  @Test
  fun missingSelfNodeClearsConfigAndIgnoresPendingResponse() = runTest {
    val config = Tailcfg.DNSConfig(Domains = listOf("a.example"))
    val (source, vm) = startWith(config)

    try {
      source.publishSelfNodeUpdate("A")
      runCurrent()
      val delayedResponse = source.requests.removeFirst()

      source.publishSelfNodeUpdate(null)
      runCurrent()
      assertNull(vm.uiState.value.dnsConfig)

      delayedResponse(Result.success(config))
      runCurrent()
      assertNull(vm.uiState.value.dnsConfig)

      source.publishSelfNodeUpdate(null)
      runCurrent()
      assertEquals(0, source.requests.size)
    } finally {
      vm.viewModelScope.cancel()
    }
  }

  @Test
  fun failedRefreshKeepsPreviousConfig() = runTest {
    val config = Tailcfg.DNSConfig(Domains = listOf("a.example"))
    val (source, vm) = startWith(config)
    try {
      source.publishSelfNodeUpdate("A")
      runCurrent()
      source.requests.removeFirst()(Result.failure(Exception("503 no netmap")))
      runCurrent()
      assertEquals(config, vm.uiState.value.dnsConfig)
    } finally {
      vm.viewModelScope.cancel()
    }
  }

  private class FakeDNSSettingsDataSource : DNSSettingsDataSource {
    override val state = MutableStateFlow(Ipn.State.Running)
    override val prefs = MutableStateFlow<Ipn.Prefs?>(Ipn.Prefs(CorpDNS = true))
    override val dnsSettingsPolicy =
        MutableStateFlow(SettingState(value = AlwaysNeverUserDecides.UserDecides, isSet = false))
    // seeded like prod, InitialStatus has already arrived by the time the screen opens
    override val selfNodeUpdate =
        MutableStateFlow<SelfNodeUpdate?>(SelfNodeUpdate(revision = 1, stableID = "A"))

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
    ) = error("Unexpected editPrefs call")

    fun publishSelfNodeUpdate(stableID: String?) {
      selfNodeUpdate.value =
          SelfNodeUpdate(
              revision = (selfNodeUpdate.value?.revision ?: 0L) + 1,
              stableID = stableID,
          )
    }
  }
}
