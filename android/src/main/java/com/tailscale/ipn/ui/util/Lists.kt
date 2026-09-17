// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.ui.theme.listItem

object Lists {
  @Composable
  fun SectionDivider(title: String? = null) {
    Box(Modifier.size(0.dp, 16.dp))
    title?.let { LargeTitle(title) }
  }

  /**
   * Separates two rows. Rows are drawn as cards with margins of their own (see [ListRow]), so what
   * separates them is that gap rather than the hairline this used to draw.
   */
  @Composable
  fun ItemDivider() {
    Box(Modifier.size(0.dp, 2.dp))
  }

  @Composable
  fun LargeTitle(
      title: String,
      bottomPadding: Dp = 0.dp,
      style: TextStyle = MaterialTheme.typography.titleMedium,
      fontWeight: FontWeight? = null,
      focusable: Boolean = false,
      backgroundColor: Color = Color.Transparent,
      fontColor: Color? = null,
  ) {
    Box(
        modifier =
            Modifier.fillMaxWidth().background(color = backgroundColor, shape = RectangleShape)
    ) {
      if (fontColor != null) {
        Text(
            text = title,
            modifier =
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding)
                    .focusable(focusable),
            style = style,
            fontWeight = fontWeight,
            color = fontColor,
        )
      } else {
        Text(
            text = title,
            modifier =
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding)
                    .focusable(focusable),
            style = style,
            fontWeight = fontWeight,
        )
      }
    }
  }

  @Composable
  fun MutedHeader(text: String) {
    Box(modifier = Modifier.fillMaxWidth()) {
      Text(
          modifier = Modifier.padding(start = 16.dp, top = 16.dp),
          text = text,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }

  @Composable
  fun InfoItem(text: CharSequence, onClick: (() -> Unit)? = null) {
    val style =
        MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
          Box(modifier = Modifier.padding(vertical = 4.dp)) {
            onClick?.let {
              Text(
                  text = text as AnnotatedString,
                  style = style,
                  modifier = Modifier.clickable { onClick() },
              )
            } ?: run { Text(text as String, style = style) }
          }
        },
    )
  }

  @Composable
  fun MultilineDescription(headlineContent: @Composable () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
          Box(modifier = Modifier.padding(vertical = 8.dp)) { headlineContent() }
        },
    )
  }
}

/**
 * The shape of a row card. Rounded like the rows in the system settings app, which is what the
 * lists in this app are read next to.
 */
val listCardShape = RoundedCornerShape(24.dp)

/**
 * A row in one of this app's lists, drawn as a rounded card over the list background with the
 * gutter and gaps the platform uses. Takes the same arguments as a Material [ListItem]; [modifier]
 * is applied inside the card, so a clickable passed here covers the card and clips its ripple.
 */
@Composable
fun ListRow(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    colors: ListItemColors = MaterialTheme.colorScheme.listItem,
) {
  ListItem(
      modifier = Modifier.listCard().then(modifier),
      headlineContent = headlineContent,
      overlineContent = overlineContent,
      supportingContent = supportingContent,
      leadingContent = leadingContent,
      trailingContent = trailingContent,
      colors = colors,
  )
}

/** Lays a row out as a card: the list gutter, the gap to the next card, and rounded corners. */
fun Modifier.listCard(): Modifier =
    this.padding(horizontal = 16.dp, vertical = 2.dp).clip(listCardShape)

/** Similar to items() but includes a horizontal divider between items. */

/** Similar to items() but includes a horizontal divider between items. */
inline fun <T> LazyListScope.itemsWithDividers(
    items: List<T>,
    noinline key: ((item: T) -> Any)? = null,
    forceLeading: Boolean = false,
    crossinline contentType: (item: T) -> Any? = { _ -> null },
    crossinline itemContent: @Composable LazyItemScope.(item: T) -> Unit,
) =
    items(
        count = items.size,
        key = if (key != null) { index: Int -> key(items[index]) } else null,
        contentType = { index -> contentType(items[index]) },
    ) {
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
    crossinline itemContent: @Composable LazyItemScope.(item: T) -> Unit,
) = itemsWithDividers(items.toList(), key, forceLeading, contentType, itemContent)
