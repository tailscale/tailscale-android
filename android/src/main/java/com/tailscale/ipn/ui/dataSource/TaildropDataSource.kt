// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.dataSource

import android.content.Context
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TaildropUsageTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

data class TaildropPeers(
    val recent: List<Tailcfg.Node>,
    val other: List<Tailcfg.Node>,
)

interface TaildropDataSource {
  val requestedTransfers: StateFlow<List<Ipn.OutgoingFile>>
  val state: StateFlow<Ipn.State>
  val outgoingFiles: StateFlow<List<Ipn.OutgoingFile>?>

  fun loadPeers(
      scope: CoroutineScope,
      completionHandler: (Result<TaildropPeers>) -> Unit,
  )

  fun sendFiles(
      peer: Tailcfg.Node,
      files: List<Ipn.OutgoingFile>,
      completionHandler: (Result<String>) -> Unit,
  )

  fun startVPN()
}

class TaildropDataSourceImpl(
    override val requestedTransfers: StateFlow<List<Ipn.OutgoingFile>>,
    context: Context,
    private val applicationScope: CoroutineScope,
) : TaildropDataSource {
  private val appContext = context.applicationContext

  override val state = Notifier.state
  override val outgoingFiles = Notifier.outgoingFiles

  override fun loadPeers(
      scope: CoroutineScope,
      completionHandler: (Result<TaildropPeers>) -> Unit,
  ) {
    Client(scope).fileTargets { result ->
      result
          .onSuccess { targets ->
            val userID = Notifier.netmap.value?.SelfNode?.User ?: 0L
            val (recent, other) =
                TaildropUsageTracker.partitionByRecency(
                    appContext,
                    userID,
                    targets.map { it.Node },
                )
            val onlineFirst =
                other.sortedWith(
                    compareByDescending<Tailcfg.Node> { it.Online ?: false }
                        .thenBy { (it.ComputedName ?: it.Name).lowercase() }
                )
            completionHandler(Result.success(TaildropPeers(recent, onlineFirst)))
          }
          .onFailure { completionHandler(Result.failure(it)) }
    }
  }

  override fun sendFiles(
      peer: Tailcfg.Node,
      files: List<Ipn.OutgoingFile>,
      completionHandler: (Result<String>) -> Unit,
  ) {
    Client(applicationScope).putTaildropFiles(appContext, peer.StableID, files) { result ->
      result.onSuccess {
        val userID = Notifier.netmap.value?.SelfNode?.User ?: 0L
        TaildropUsageTracker.updateLastUsed(appContext, userID, peer.StableID)
      }
      completionHandler(result)
    }
  }

  override fun startVPN() {
    UninitializedApp.get().startVPN()
  }
}
