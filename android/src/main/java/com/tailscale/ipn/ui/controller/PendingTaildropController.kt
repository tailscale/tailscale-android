// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.controller

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.tailscale.ipn.TaildropDirectoryStore
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.PendingTaildropItem
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.notifier.TaildropNotifier
import com.tailscale.ipn.util.InlineShare
import com.tailscale.ipn.util.InlineShareConsumer
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PendingTaildropController(
    scope: CoroutineScope,
) {
  private val TAG = "PendingTaildropController"

  private val consumedFileIds = MutableStateFlow<Set<String>>(emptySet())

  // Go drops files from incomingFiles soon after they finish, so we accumulate
  // Done=true entries here once and let the user act on them.
  private val _pendingFiles = MutableStateFlow<List<Ipn.PartialFile>>(emptyList())

  val pendingFiles: StateFlow<List<Ipn.PartialFile>> =
      _pendingFiles
          .combine(consumedFileIds) { files, consumed ->
            files.filter { PendingTaildropItem.File(it).id !in consumed }
          }
          .stateIn(scope, SharingStarted.Eagerly, emptyList())

  val pendingItems: StateFlow<List<PendingTaildropItem>> =
      Notifier.inlineShareInbox
          .combine(pendingFiles) { shares, files ->
            files.map { PendingTaildropItem.File(it) } +
                shares.map { PendingTaildropItem.InlineShareItem(it) }
          }
          .stateIn(scope, SharingStarted.Eagerly, emptyList())

  val isPresentingPendingItemsList = MutableStateFlow(false)

  init {
    scope.launch {
      Notifier.incomingFiles.collect { list ->
        val arrivals =
            list.orEmpty().filter { f ->
              f.Done == true && !InlineShare.matches(f.Name) && !f.Name.endsWith(".partial")
            }
        if (arrivals.isEmpty()) return@collect
        _pendingFiles.update { current ->
          val knownIds = current.map { PendingTaildropItem.File(it).id }.toSet()
          current + arrivals.filter { PendingTaildropItem.File(it).id !in knownIds }
        }
      }
    }
    // Auto-dismiss the bottom sheet when the queue empties out, so the dismissal
    // tracks pendingItems instead of relying on a stale .value check after each consume.
    scope.launch {
      pendingItems.collect { items ->
        if (items.isEmpty()) isPresentingPendingItemsList.value = false
      }
    }
  }

  fun handleBannerTap(context: Context) {
    val items = pendingItems.value
    if (items.size >= 2) {
      isPresentingPendingItemsList.value = true
      return
    }
    items.firstOrNull()?.let { consume(context, it) }
  }

  fun consume(context: Context, item: PendingTaildropItem) {
    when (item) {
      is PendingTaildropItem.File -> {
        openFile(context, item.partial)
        consumedFileIds.update { it + item.id }
      }
      is PendingTaildropItem.InlineShareItem -> {
        // Leave the entry alone if nothing happened, so the user can retry.
        if (!InlineShareConsumer.consume(context, item.share)) return
        Notifier.removeInlineShare(item.share.id)
        TaildropNotifier.cancel(context, item.share.id)
      }
    }
  }

  fun dismiss(context: Context, item: PendingTaildropItem) {
    when (item) {
      is PendingTaildropItem.File -> consumedFileIds.update { it + item.id }
      is PendingTaildropItem.InlineShareItem -> {
        InlineShareConsumer.dismiss(context, item.share)
        Notifier.removeInlineShare(item.share.id)
        TaildropNotifier.cancel(context, item.share.id)
      }
    }
  }

  private fun openFile(context: Context, partial: Ipn.PartialFile) {
    val uri =
        partial.FinalPath?.let { runCatching { it.toUri() }.getOrNull() }
            ?: return openTaildropFolder(context)
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"
    val intent =
        Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(uri, mime)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    try {
      context.startActivity(intent)
    } catch (e: Exception) {
      TSLog.w(TAG, "openFile fallback to folder: $e")
      openTaildropFolder(context)
    }
  }

  fun openTaildropFolder(context: Context) {
    val treeUri =
        runCatching { TaildropDirectoryStore.loadSavedDir() }.getOrNull()
            ?: run {
              TSLog.w(TAG, "openTaildropFolder: no saved Taildrop dir")
              return
            }
    val docUri =
        runCatching {
          DocumentsContract.buildDocumentUriUsingTree(
              treeUri,
              DocumentsContract.getTreeDocumentId(treeUri),
          )
        }
            .getOrNull() ?: treeUri
    val intent =
        Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(docUri, "vnd.android.document/directory")
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    try {
      context.startActivity(intent)
    } catch (e: Exception) {
      TSLog.w(TAG, "openTaildropFolder failed: $e")
    }
  }
}
