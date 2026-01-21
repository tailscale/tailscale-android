// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.ui.util.InstalledApp
import com.tailscale.ipn.ui.util.InstalledAppsManager
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SplitTunnelAppPickerViewModel : ViewModel() {
  val installedAppsManager = InstalledAppsManager(packageManager = App.get().packageManager)
  val excludedPackageNames: StateFlow<List<String>> = MutableStateFlow(listOf())
    val includedPackageNames: StateFlow<List<String>> = MutableStateFlow(listOf())
  val installedApps: StateFlow<List<InstalledApp>> = MutableStateFlow(listOf())
  val mdmExcludedPackages: StateFlow<SettingState<String?>> = MDMSettings.excludedPackages.flow
  val mdmIncludedPackages: StateFlow<SettingState<String?>> = MDMSettings.includedPackages.flow

  private var saveJob: Job? = null




  init {
    installedApps.set(installedAppsManager.fetchInstalledApps())
    excludedPackageNames.set(
        App.get()
            .disallowedPackageNames()
            .intersect(installedApps.value.map { it.packageName }.toSet())
            .toList())
      includedPackageNames.set(
          App.get()
              .allowedPackageNames()
              .intersect(installedApps.value.map { it.packageName }.toSet())
              .toList())

  }

  fun exclude(packageName: String) {
    if (excludedPackageNames.value.contains(packageName)) return
    excludedPackageNames.set(excludedPackageNames.value + packageName)
    debounceSave()
  }

  fun unexclude(packageName: String) {
    excludedPackageNames.set(excludedPackageNames.value - packageName)
    debounceSave()
  }

    fun include(packageName: String) {
        if (includedPackageNames.value.contains(packageName)) return
        includedPackageNames.set(includedPackageNames.value + packageName)
        debounceSaveInclude()
    }

    fun uninclude(packageName: String) {
        includedPackageNames.set(includedPackageNames.value - packageName)
        debounceSaveInclude()
    }

    private fun debounceSave() {
    saveJob?.cancel()
    saveJob =
        viewModelScope.launch {
          delay(500) // Wait to batch multiple rapid updates
          App.get().updateUserDisallowedPackageNames(excludedPackageNames.value)
        }
  }

    private fun debounceSaveInclude() {
        saveJob?.cancel()
        saveJob =
            viewModelScope.launch {
                delay(500)
                App.get().updateUserAllowedPackageNames(includedPackageNames.value)
            }
    }


    fun toggleSplitTunnel() {
        val newValue = !App.get().isSplitTunnelEnabled()
        App.get().setSplitTunnelEnabled(newValue)
    }

    // If MDM inforces split tunnel — write it to sharedprefs
    private fun enforceMdMSplitTunnel() {
        val mdmActive =
            mdmExcludedPackages.value.value?.isNotEmpty() == true ||
                    mdmIncludedPackages.value.value?.isNotEmpty() == true

        if (mdmActive && !App.get().isSplitTunnelEnabled()) {
            App.get().setSplitTunnelEnabled(true)
        }
    }


}
