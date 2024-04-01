// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

object Lists {
  @Composable
  fun SectionDivider(title: String? = null) {
    Box(Modifier.size(0.dp, 16.dp))
    title?.let {
      ListItem(headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) })
    }
  }

  @Composable
  fun ItemDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
  }
}

/** Similar to items() but includes a horizontal divider between items. */
inline fun <T> LazyListScope.itemsWithDividers(
    items: List<T>,
    noinline key: ((item: T) -> Any)? = null,
    forceLeading: Boolean = false,
    crossinline contentType: (item: T) -> Any? = { _ -> null },
    crossinline itemContent: @Composable LazyItemScope.(item: T) -> Unit
) =
    items(
        count = items.size,
        key = if (key != null) { index: Int -> key(items[index]) } else null,
        contentType = { index -> contentType(items[index]) }) {
          if (forceLeading && it == 0 || it > 0 && it < items.size) {
            Lists.ItemDivider()
          }
          itemContent(items[it])
        }

inline fun <T> LazyListScope.itemsWithDividers(
    items: Array<T>,
    noinline key: ((item: T) -> Any)? = null,
    forceLeading: Boolean = false,
    crossinline contentType: (item: T) -> Any? = { _ -> null },
    crossinline itemContent: @Composable LazyItemScope.(item: T) -> Unit
) = itemsWithDividers(items.toList(), key, forceLeading, contentType, itemContent)
