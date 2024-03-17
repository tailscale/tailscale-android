// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause


package com.tailscale.ipn.ui.viewModel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.service.IpnModel
import com.tailscale.ipn.ui.service.setExitNodeId
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.TreeMap

class ExitNodePickerViewModel(private val model: IpnModel, private val onNavigateHome: () -> Unit) :
    ViewModel() {
    companion object {
        const val TAG = "ExitNodePickerViewModel"
    }

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
    val mullvadExitNodesByCountryCode: StateFlow<Map<String, List<ExitNode>>> = MutableStateFlow(
        TreeMap()
    )
    val mullvadBestAvailableByCountry: StateFlow<Map<String, ExitNode>> = MutableStateFlow(
        TreeMap()
    )
    val anyActive: StateFlow<Boolean> = MutableStateFlow(false)

    init {
        Client(viewModelScope).status { result ->
            result.onFailure {
                Log.e(TAG, "getStatus: ${it.message}")
            }.onSuccess {
                it.Peer?.values?.let { peers ->
                    val allNodes = peers.filter { it.ExitNodeOption }.map {
                        ExitNode(
                            id = it.ID,
                            label = it.DNSName,
                            online = it.Online,
                            selected = it.ExitNode,
                            mullvad = it.DNSName.endsWith(".mullvad.ts.net."),
                            priority = it.Location?.Priority ?: 0,
                            countryCode = it.Location?.CountryCode ?: "",
                            country = it.Location?.Country ?: "",
                            city = it.Location?.City ?: "",
                        )
                    }

                    val tailnetNodes = allNodes.filter { !it.mullvad }
                    tailnetExitNodes.set(tailnetNodes.sortedWith { a, b ->
                        a.label.compareTo(
                            b.label
                        )
                    })

                    val mullvadExitNodes = allNodes.filter {
                        // Pick all mullvad nodes that are online or the currently selected
                        it.mullvad && (it.selected || it.online)
                    }.groupBy {
                        // Group by countryCode
                        it.countryCode
                    }.mapValues { (_, nodes) ->
                        // Group by city
                        nodes.groupBy {
                            it.city
                        }.mapValues { (_, nodes) ->
                            // Pick one node per city, either the selected one or the best
                            // available
                            nodes.sortedWith { a, b ->
                                if (a.selected && !b.selected) {
                                    -1
                                } else if (b.selected && !a.selected) {
                                    1
                                } else {
                                    b.priority.compareTo(a.priority)
                                }
                            }.first()
                        }.values.sortedBy { it.city.lowercase() }
                    }
                    mullvadExitNodesByCountryCode.set(mullvadExitNodes)

                    val bestAvailableByCountry = mullvadExitNodes.mapValues { (_, nodes) ->
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
        model.setExitNodeId(node.id) {
            onNavigateHome()
            LoadingIndicator.stop()
        }
    }
}