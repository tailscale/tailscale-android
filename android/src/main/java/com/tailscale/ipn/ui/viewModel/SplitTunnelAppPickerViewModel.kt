// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.ui.util.InstalledApp
import com.tailscale.ipn.ui.util.InstalledAppsManager
import com.tailscale.ipn.ui.util.orderedSplitTunnelApps
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SplitTunnelAppPickerViewModel : ViewModel() {
  val installedAppsManager = InstalledAppsManager(packageManager = App.get().packageManager)

  val installedApps: StateFlow<List<InstalledApp>> =
      flow {
            emit(installedAppsManager.fetchInstalledApps())
            initSelectedPackageNames()
          }
          .flowOn(Dispatchers.IO)
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(5000),
              initialValue = listOf(),
          )
  val selectedPackageNames: StateFlow<Set<String>> = MutableStateFlow(emptySet())
  val searchQuery: StateFlow<String> = MutableStateFlow("")
  val appIcons: StateFlow<Map<String, Bitmap>> = MutableStateFlow(emptyMap())
  val displayedApps: StateFlow<List<InstalledApp>> =
      combine(installedApps, selectedPackageNames, searchQuery) { apps, selectedPackages, query ->
            orderedSplitTunnelApps(apps, selectedPackages, query)
          }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(5000),
              initialValue = listOf(),
          )

  val allowSelected: StateFlow<Boolean> = MutableStateFlow(App.get().allowSelectedPackages())
  val showHeaderMenu: StateFlow<Boolean> = MutableStateFlow(false)
  val showSwitchDialog: StateFlow<Boolean> = MutableStateFlow(false)

  val mdmExcludedPackages: StateFlow<SettingState<String?>> = MDMSettings.excludedPackages.flow
  val mdmIncludedPackages: StateFlow<SettingState<String?>> = MDMSettings.includedPackages.flow

  private var saveJob: Job? = null
  private var preloadIconsJob: Job? = null

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
            .toSet())
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

  fun updateSearchQuery(query: String) {
    searchQuery.set(query)
  }

  fun preloadIcons(apps: List<InstalledApp>, sizePx: Int) {
    if (apps.isEmpty() || sizePx <= 0) return

    preloadIconsJob?.cancel()
    preloadIconsJob =
        viewModelScope.launch(Dispatchers.IO) {
          val missingApps = apps.filterNot { appIcons.value.containsKey(it.packageName) }
          val loadedIcons = mutableMapOf<String, Bitmap>()

          missingApps.forEachIndexed { index, app ->
            loadedIcons[app.packageName] =
                installedAppsManager.iconForPackage(app.packageName, sizePx)

            if (loadedIcons.size >= 20 || index == missingApps.lastIndex) {
              appIcons.set(appIcons.value + loadedIcons)
              loadedIcons.clear()
            }
          }
        }
  }

  private fun debounceSave() {
    saveJob?.cancel()
    saveJob =
        viewModelScope.launch {
          delay(500) // Wait to batch multiple rapid updates
          App.get().updateUserSelectedPackages(selectedPackageNames.value.toList())
        }
  }
}
