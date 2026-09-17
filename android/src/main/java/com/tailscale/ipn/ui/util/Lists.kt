// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.theme.selectedListItem
import com.tailscale.ipn.ui.util.AndroidTVUtil.isAndroidTV

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
 * Where a row sits in a run of related rows. A lazy list cannot hold a run in one [ListGroup]
 * without composing all of it, so its rows carry their place in the run instead and round only the
 * corners at the ends of it.
 */
enum class RowPosition {
  /** The only row in its run, rounded like a card of its own. */
  Single,
  First,
  Middle,
  Last,
}

private val innerCorner = 6.dp

private fun RowPosition.shape() =
    when (this) {
      RowPosition.Single -> listCardShape
      RowPosition.First ->
          RoundedCornerShape(
              topStart = 24.dp,
              topEnd = 24.dp,
              bottomStart = innerCorner,
              bottomEnd = innerCorner,
          )
      RowPosition.Middle -> RoundedCornerShape(innerCorner)
      RowPosition.Last ->
          RoundedCornerShape(
              topStart = innerCorner,
              topEnd = innerCorner,
              bottomStart = 24.dp,
              bottomEnd = 24.dp,
          )
    }

/** The place of [item] in this run of rows, for [ListRow]'s position argument. */
fun <T> List<T>.rowPosition(item: T): RowPosition {
  val index = indexOf(item)
  return when {
    size <= 1 -> RowPosition.Single
    index == 0 -> RowPosition.First
    index == size - 1 -> RowPosition.Last
    else -> RowPosition.Middle
  }
}

/** True for the rows inside a [ListGroup], which draws the card that holds them. */
private val LocalGroupedRows = compositionLocalOf { false }

/**
 * Holds a run of related rows in one card, rounded at the top and bottom of the run the way the
 * system settings app groups its entries. Rows inside are flush with the card and separated by the
 * gap [Lists.ItemDivider] leaves; a selected row still draws a pill of its own.
 */
@Composable
fun ListGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  CompositionLocalProvider(LocalGroupedRows provides true) {
    Column(modifier = modifier.listCard(), content = content)
  }
}

/**
 * A row in one of this app's lists. On its own it is a rounded card over the list background with
 * the gutter and gaps the platform uses; inside a [ListGroup] it is flush with the group's card
 * instead. A [selected] row is always a fully rounded pill, so the row whose detail is on screen
 * reads as picked out of its group.
 *
 * Takes the same arguments as a Material [ListItem]; [modifier] is applied inside the card, so a
 * clickable passed here covers the card and clips its ripple.
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
    selected: Boolean = false,
    position: RowPosition = RowPosition.Single,
) {
  val grouped = LocalGroupedRows.current
  // A remote has no cursor, so on a TV the row the D-pad is on has to say so. Only the focus is
  // observed here; the clickable the caller passes is what takes it.
  var focused by remember { mutableStateOf(false) }
  val onTV = isAndroidTV()
  val card =
      when {
        // A group already provides the gutter, so a pill inside one only needs its corners.
        selected && grouped -> Modifier.clip(listCardShape)
        grouped -> Modifier
        // A selected row is a pill, whatever its place in the run.
        selected || position == RowPosition.Single -> Modifier.listCard()
        else -> Modifier.padding(horizontal = 16.dp, vertical = 1.dp).clip(position.shape())
      }
  ListItem(
      modifier =
          card.conditional(onTV, { onFocusChanged { focused = it.isFocused } }).then(modifier),
      headlineContent = headlineContent,
      overlineContent = overlineContent,
      supportingContent = supportingContent,
      leadingContent = leadingContent,
      trailingContent = trailingContent,
      colors =
          if (selected || (onTV && focused)) MaterialTheme.colorScheme.selectedListItem else colors,
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
