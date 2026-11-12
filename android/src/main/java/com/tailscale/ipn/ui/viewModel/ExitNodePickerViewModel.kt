// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.dataSource.ExitNodePickerDataSource
import com.tailscale.ipn.ui.model.ExitNode
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.util.AdvertisedRoutesHelper
import java.util.TreeMap
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

data class ExitNodePickerNav(
    val onNavigateBackHome: () -> Unit,
    val onNavigateBackToExitNodes: () -> Unit,
    val onNavigateToMullvad: () -> Unit,
    val onNavigateToMullvadInfo: () -> Unit,
    val onNavigateBackToMullvad: () -> Unit,
    val onNavigateToMullvadCountry: (String) -> Unit,
    val onNavigateToRunAsExitNode: () -> Unit,
)

class ExitNodePickerViewModelFactory(private val dataSource: ExitNodePickerDataSource) :
    ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(ExitNodePickerViewModel::class.java)) {
      return ExitNodePickerViewModel(dataSource) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}

class ExitNodePickerViewModel(private val dataSource: ExitNodePickerDataSource) : ViewModel() {
  private val _tailnetExitNodes = MutableStateFlow<List<ExitNode>>(emptyList())
  val tailnetExitNodes: StateFlow<List<ExitNode>> = _tailnetExitNodes.asStateFlow()
  private val _mullvadExitNodesByCountryCode =
      MutableStateFlow<Map<String, List<ExitNode>>>(TreeMap())
  val mullvadExitNodesByCountryCode: StateFlow<Map<String, List<ExitNode>>> =
      _mullvadExitNodesByCountryCode.asStateFlow()
  private val _mullvadBestAvailableByCountry = MutableStateFlow<Map<String, ExitNode>>(TreeMap())
  val mullvadBestAvailableByCountry: StateFlow<Map<String, ExitNode>> =
      _mullvadBestAvailableByCountry.asStateFlow()
  private val _mullvadExitNodeCount = MutableStateFlow(0)
  val mullvadExitNodeCount: StateFlow<Int> = _mullvadExitNodeCount.asStateFlow()
  private val _anyActive = MutableStateFlow(false)
  val anyActive: StateFlow<Boolean> = _anyActive.asStateFlow()
  private val _shouldShowMullvadInfo = MutableStateFlow(false)
  val shouldShowMullvadInfo: StateFlow<Boolean> = _shouldShowMullvadInfo.asStateFlow()

  val isRunningExitNode: StateFlow<Boolean> =
      dataSource.prefs
          .map { prefs -> prefs?.let { AdvertisedRoutesHelper.exitNodeOnFromPrefs(it) } ?: false }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

  init {
    viewModelScope.launch {
      combine(dataSource.netmap, dataSource.prefs) { netmap, prefs -> netmap to prefs }
          .collect { (netmap, prefs) ->
            val exitNodeId = prefs?.activeExitNodeID ?: prefs?.selectedExitNodeID
            netmap?.Peers?.let { peers ->
              val allNodes =
                  peers
                      .filter { it.isExitNode }
                      .map {
                        ExitNode(
                            id = it.StableID,
                            label = it.displayName,
                            online = MutableStateFlow(it.Online ?: false),
                            selected = it.StableID == exitNodeId,
                            mullvad = it.Name.endsWith(".mullvad.ts.net."),
                            priority = it.Hostinfo.Location?.Priority ?: 0,
                            countryCode = it.Hostinfo.Location?.CountryCode ?: "",
                            country = it.Hostinfo.Location?.Country ?: "",
                            city = it.Hostinfo.Location?.City ?: "",
                        )
                      }

              val tailnetNodes = allNodes.filter { !it.mullvad }
              _tailnetExitNodes.value = tailnetNodes.sortedWith { a, b ->
                a.label.compareTo(b.label)
              }

              val allMullvadExitNodes = allNodes.filter { node ->
                // Pick all mullvad nodes that are online or the currently selected
                val online = node.online.value
                node.mullvad && (node.selected || online)
              }
              val mullvadExitNodes =
                  allMullvadExitNodes
                      .groupBy {
                        // Group by countryCode
                        it.countryCode
                      }
                      .mapValues { (_, nodes) ->
                        // Group by city
                        nodes
                            .groupBy { it.city }
                            .mapValues { (_, nodes) ->
                              // Pick one node per city, either the selected one or the best
                              // available
                              nodes
                                  .sortedWith { a, b ->
                                    if (a.selected && !b.selected) {
                                      -1
                                    } else if (b.selected && !a.selected) {
                                      1
                                    } else {
                                      b.priority.compareTo(a.priority)
                                    }
                                  }
                                  .first()
                            }
                            .values
                            .sortedBy { it.city.lowercase() }
                      }
              _mullvadExitNodesByCountryCode.value = mullvadExitNodes
              _mullvadExitNodeCount.value = allMullvadExitNodes.size

              val bestAvailableByCountry = mullvadExitNodes.mapValues { (_, nodes) ->
                nodes.minByOrNull { -1 * it.priority }!!
              }
              _mullvadBestAvailableByCountry.value = bestAvailableByCountry
              _anyActive.value = allNodes.any { it.selected }

              prefs?.let { prefs ->
                // Only show the Mullvad info view if the user is an admin and is using a Tailscale
                // control server, as it wouldn't be actionable information otherwise.
                _shouldShowMullvadInfo.value =
                    netmap.SelfNode.isAdmin && prefs.ControlURL.endsWith(".tailscale.com")
              }
            }
          }
    }
  }

  suspend fun setExitNode(node: ExitNode) {
    val prefsOut = Ipn.MaskedPrefs()
    prefsOut.ExitNodeID = node.id

    suspendCancellableCoroutine<Unit> { continuation ->
      dataSource.editPrefs(viewModelScope, prefsOut) { continuation.resume(Unit) }
    }
  }

  suspend fun toggleAllowLANAccess(): Result<Ipn.Prefs> {
    val prefs = dataSource.prefs.value ?: return Result.failure(Exception("no prefs"))

    val prefsOut = Ipn.MaskedPrefs()
    prefsOut.ExitNodeAllowLANAccess = !prefs.ExitNodeAllowLANAccess

    return suspendCancellableCoroutine { continuation ->
      dataSource.editPrefs(viewModelScope, prefsOut) { result -> continuation.resume(result) }
    }
  }
}

val List<ExitNode>.selected
  get() = this.any { it.selected }

val Map<String, List<ExitNode>>.selected
  get() = this.any { it.value.selected }
