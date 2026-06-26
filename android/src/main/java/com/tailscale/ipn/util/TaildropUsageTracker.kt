// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.util

import android.content.Context
import com.tailscale.ipn.ui.model.Tailcfg
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

// Persists recent Taildrop recipients per user. Mirrors iOS TaildropUsageTracker.
object TaildropUsageTracker {
  private const val PREFS_NAME = "TaildropUsage"
  private const val KEY = "lastUsedByUser"
  private const val MAX_TRACKED = 3

  private val serializer =
      MapSerializer(String.serializer(), MapSerializer(String.serializer(), Long.serializer()))

  fun getLastUsed(context: Context, userID: Long, stableID: String): Long? =
      loadAll(context)[userID.toString()]?.get(stableID)

  fun updateLastUsed(context: Context, userID: Long, stableID: String) {
    val all = loadAll(context).toMutableMap()
    val byStable = (all[userID.toString()] ?: emptyMap()).toMutableMap()
    byStable[stableID] = System.currentTimeMillis()
    val capped =
        byStable.entries.sortedByDescending { it.value }.take(MAX_TRACKED).associate {
          it.key to it.value
        }
    all[userID.toString()] = capped
    saveAll(context, all)
  }

  fun partitionByRecency(
      context: Context,
      userID: Long,
      peers: List<Tailcfg.Node>,
  ): Pair<List<Tailcfg.Node>, List<Tailcfg.Node>> {
    val sorted = sortPeers(context, userID, peers)
    val pivot =
        sorted.indexOfFirst { getLastUsed(context, userID, it.StableID) == null }.let {
          if (it < 0) sorted.size else it
        }
    return sorted.subList(0, pivot).toList() to sorted.subList(pivot, sorted.size).toList()
  }

  fun sortPeers(
      context: Context,
      userID: Long,
      peers: List<Tailcfg.Node>,
  ): List<Tailcfg.Node> =
      peers.sortedWith { a, b ->
        val ta = getLastUsed(context, userID, a.StableID)
        val tb = getLastUsed(context, userID, b.StableID)
        when {
          ta != null && tb != null -> tb.compareTo(ta)
          ta != null -> -1
          tb != null -> 1
          else ->
              (a.ComputedName ?: a.Name)
                  .compareTo(b.ComputedName ?: b.Name, ignoreCase = true)
        }
      }

  private fun loadAll(context: Context): Map<String, Map<String, Long>> {
    val raw = prefs(context).getString(KEY, null) ?: return emptyMap()
    return runCatching { Json.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
  }

  private fun saveAll(context: Context, data: Map<String, Map<String, Long>>) {
    prefs(context).edit().putString(KEY, Json.encodeToString(serializer, data)).apply()
  }

  private fun prefs(context: Context) =
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
