// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.TreeMap

data class ExitNodePickerNav(
    val onNavigateBackHome: () -> Unit,
    val onNavigateBackToExitNodes: () -> Unit,
    val onNavigateToMullvad: () -> Unit,
    val onNavigateBackToMullvad: () -> Unit,
    val onNavigateToMullvadCountry: (String) -> Unit,
    val onNavigateToRunAsExitNode: () -> Unit,
)

class ExitNodePickerViewModelFactory(private val nav: ExitNodePickerNav) :
    ViewModelProvider.Factory {
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return ExitNodePickerViewModel(nav) as T
  }
}

class ExitNodePickerViewModel(private val nav: ExitNodePickerNav) : IpnViewModel() {
  data class ExitNode(
      val id: StableNodeID? = null,
      val label: String,
      val online: Boolean,
      val selected: Boolean,
      val mullvad: Boolean = false,
      val priority: Int = 0,
      val countryCode: String = "",
      val country: String = "",
      val city: String = ""
  )

  val tailnetExitNodes: StateFlow<List<ExitNode>> = MutableStateFlow(emptyList())
  val mullvadExitNodesByCountryCode: StateFlow<Map<String, List<ExitNode>>> =
      MutableStateFlow(TreeMap())
  val mullvadBestAvailableByCountry: StateFlow<Map<String, ExitNode>> = MutableStateFlow(TreeMap())
  val mullvadExitNodeCount: StateFlow<Int> = MutableStateFlow(0)
  val anyActive: StateFlow<Boolean> = MutableStateFlow(false)
  val isRunningExitNode: StateFlow<Boolean> = MutableStateFlow(false)

  init {
    viewModelScope.launch {
      Notifier.netmap
          .combine(Notifier.prefs) { netmap, prefs -> Pair(netmap, prefs) }
          .stateIn(viewModelScope)
          .collect { (netmap, prefs) ->
            isRunningExitNode.set(prefs?.let { AdvertisedRoutesHelper.exitNodeOnFromPrefs(it) })
            val exitNodeId = prefs?.activeExitNodeID ?: prefs?.selectedExitNodeID
            netmap?.Peers?.let { peers ->
              val allNodes =
                  peers
                      .filter { it.isExitNode }
                      .map {
                        ExitNode(
                            id = it.StableID,
                            label = it.displayName,
                            online = it.Online ?: false,
                            selected = it.StableID == exitNodeId,
                            mullvad = it.Name.endsWith(".mullvad.ts.net."),
                            priority = it.Hostinfo.Location?.Priority ?: 0,
                            countryCode = it.Hostinfo.Location?.CountryCode ?: "",
                            country = it.Hostinfo.Location?.Country ?: "",
                            city = it.Hostinfo.Location?.City ?: "",
                        )
                      }

              val tailnetNodes = allNodes.filter { !it.mullvad }
              tailnetExitNodes.set(tailnetNodes.sortedWith { a, b -> a.label.compareTo(b.label) })

              val allMullvadExitNodes =
                  allNodes.filter {
                    // Pick all mullvad nodes that are online or the currently selected
                    it.mullvad && (it.selected || it.online)
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
              mullvadExitNodesByCountryCode.set(mullvadExitNodes)
              mullvadExitNodeCount.set(allMullvadExitNodes.size)

              val bestAvailableByCountry =
                  mullvadExitNodes.mapValues { (_, nodes) ->
                    nodes.minByOrNull { -1 * it.priority }!!
                  }
              mullvadBestAvailableByCountry.set(bestAvailableByCountry)

              anyActive.set(allNodes.any { it.selected })
            }
          }
    }
  }

  fun setExitNode(node: ExitNode) {
    LoadingIndicator.start()
    val prefsOut = Ipn.MaskedPrefs()
    prefsOut.ExitNodeID = node.id

    Client(viewModelScope).editPrefs(prefsOut) {
      nav.onNavigateBackToExitNodes()
      LoadingIndicator.stop()
    }
  }

  fun toggleAllowLANAccess(callback: (Result<Ipn.Prefs>) -> Unit) {
    val prefs =
        Notifier.prefs.value
            ?: run {
              callback(Result.failure(Exception("no prefs")))
              return@toggleAllowLANAccess
            }

    val prefsOut = Ipn.MaskedPrefs()
    prefsOut.ExitNodeAllowLANAccess = !prefs.ExitNodeAllowLANAccess
    Client(viewModelScope).editPrefs(prefsOut, callback)
  }
}

val List<ExitNodePickerViewModel.ExitNode>.selected
  get() = this.any { it.selected }

val Map<String, List<ExitNodePickerViewModel.ExitNode>>.selected
  get() = this.any { it.value.selected }
