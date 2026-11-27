// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.controller

import com.tailscale.ipn.ui.dataSource.ExitNodeStatusDataSource
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.NodeState
import com.tailscale.ipn.ui.util.AdvertisedRoutesHelper
import com.tailscale.ipn.util.TSLog
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

class ExitNodeController(
    private val dataSource: ExitNodeStatusDataSource,
    private val scope: CoroutineScope,
) {
  private val tag = "ExitNodeController"

  private var lastPrefs: Ipn.Prefs? = null
  private val _isRunningExitNode = MutableStateFlow(false)

  private val _nodeState = MutableStateFlow(NodeState.NONE)
  val nodeState: StateFlow<NodeState> = _nodeState.asStateFlow()

  init {
    scope.launch {
      dataSource.prefs.collect { currentPrefs ->
        currentPrefs?.let {
          lastPrefs = it
          _isRunningExitNode.value = AdvertisedRoutesHelper.exitNodeOnFromPrefs(it)
        }
      }
    }

    scope.launch {
      combine(
              dataSource.prefs,
              dataSource.netmap,
              _isRunningExitNode,
          ) { prefs, netmap, isRunningExitNode ->
            val validPrefs = prefs ?: return@combine NodeState.NONE
            val validNetmap = netmap ?: return@combine NodeState.NONE

            val chosenExitNodeId = validPrefs.activeExitNodeID ?: validPrefs.selectedExitNodeID
            val exitNodePeer = chosenExitNodeId?.let { id ->
              validNetmap.Peers?.find { it.StableID == id }
            }

            when {
              exitNodePeer?.Online == false -> {
                when {
                  dataSource.forcedExitNodeId() != null -> NodeState.OFFLINE_MDM
                  validPrefs.activeExitNodeID != null -> NodeState.OFFLINE_ENABLED
                  else -> NodeState.OFFLINE_DISABLED
                }
              }

              exitNodePeer != null -> {
                if (!validPrefs.activeExitNodeID.isNullOrEmpty()) {
                  NodeState.ACTIVE_AND_RUNNING
                } else {
                  NodeState.ACTIVE_NOT_RUNNING
                }
              }

              isRunningExitNode -> NodeState.RUNNING_AS_EXIT_NODE
              else -> NodeState.NONE
            }
          }
          .collect { nodeState -> _nodeState.value = nodeState }
    }
  }

  suspend fun toggleExitNode() {
    val currentPrefs = dataSource.prefs.value ?: return

    val enabled =
        when {
          currentPrefs.activeExitNodeID != null -> false
          currentPrefs.selectedExitNodeID != null -> true
          else -> {
            TSLog.e(tag, "No exit node to disable and no prior exit node to enable")
            return
          }
        }

    suspendCancellableCoroutine<Unit> { continuation ->
      dataSource.setUseExitNode(scope, enabled) { continuation.resume(Unit) }
    }
  }

  suspend fun setRunningExitNode(isOn: Boolean) {
    val currentPrefs = lastPrefs ?: return

    suspendCancellableCoroutine<Unit> { continuation ->
      dataSource.setRunningExitNode(
          scope = scope,
          currentPrefs = currentPrefs,
          isOn = isOn,
      ) { result ->
        TSLog.d(tag, "Edited prefs: $result")
        continuation.resume(Unit)
      }
    }
  }
}
