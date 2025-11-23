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

  val installedApps: StateFlow<List<InstalledApp>> = MutableStateFlow(listOf())
  val selectedPackageNames: StateFlow<List<String>> = MutableStateFlow(listOf())

  val allowSelected: StateFlow<Boolean> = MutableStateFlow(false)
  val showHeaderMenu: StateFlow<Boolean> = MutableStateFlow(false)
  val showSwitchDialog: StateFlow<Boolean> = MutableStateFlow(false)

  val mdmExcludedPackages: StateFlow<SettingState<String?>> = MDMSettings.excludedPackages.flow
  val mdmIncludedPackages: StateFlow<SettingState<String?>> = MDMSettings.includedPackages.flow

  private var saveJob: Job? = null

  init {
    installedApps.set(installedAppsManager.fetchInstalledApps())
    initSelectedPackageNames()
  }

  private fun initSelectedPackageNames() {
    allowSelected.set(App.get().allowSelectedPackages())
    selectedPackageNames.set(
        App.get()
            .selectedPackageNames()
            .let {
              if (!allowSelected.value) {
                it.union(App.get().builtInDisallowedPackageNames)
              } else {
                it
              }
            }
            .intersect(installedApps.value.map { it.packageName }.toSet())
            .toList())
  }

  fun performSelectionSwitch() {
    App.get().switchUserSelectedPackages()
    initSelectedPackageNames()
  }

  fun select(packageName: String) {
    if (selectedPackageNames.value.contains(packageName)) return

    selectedPackageNames.set(selectedPackageNames.value + packageName)
    debounceSave()
  }

  fun deselect(packageName: String) {
    selectedPackageNames.set(selectedPackageNames.value - packageName)
    debounceSave()
  }

  private fun debounceSave() {
    saveJob?.cancel()
    saveJob =
        viewModelScope.launch {
          delay(500) // Wait to batch multiple rapid updates
          App.get().updateUserSelectedPackages(selectedPackageNames.value)
        }
  }
}
