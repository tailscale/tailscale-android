// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.controller.PendingTaildropController
import com.tailscale.ipn.ui.model.PendingTaildropItem
import com.tailscale.ipn.ui.theme.onWarning
import com.tailscale.ipn.ui.theme.warning
import com.tailscale.ipn.util.InlineShare
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

@Composable
fun TaildropBannerView(viewModel: PendingTaildropController) {
  val items by viewModel.pendingItems.collectAsState()
  val context = LocalContext.current

  val onlyItem = items.singleOrNull()
  val inlineShareItem = onlyItem as? PendingTaildropItem.InlineShareItem

  val density = LocalDensity.current
  val dismissThresholdPx = with(density) { 60.dp.toPx() }
  val flingPx = with(density) { 800.dp.toPx() }
  val flingTriggerPx = with(density) { 667.dp.toPx() }
  val fadeRangePx = with(density) { 200.dp.toPx() }

  var dragOffsetPx by remember { mutableFloatStateOf(0f) }
  val animatedOffset by
      animateFloatAsState(targetValue = dragOffsetPx, animationSpec = tween(durationMillis = 60))
  val opacity = 1f - (abs(animatedOffset) / fadeRangePx).coerceAtMost(0.5f)

  LaunchedEffect(onlyItem?.id) { dragOffsetPx = 0f }

  LaunchedEffect(dragOffsetPx, inlineShareItem?.id) {
    if (inlineShareItem != null && abs(dragOffsetPx) >= flingTriggerPx) {
      delay(120.milliseconds)
      viewModel.dismiss(context, inlineShareItem)
      dragOffsetPx = 0f
    }
  }

  Surface(
      modifier =
          Modifier.fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 8.dp)
              .offset { IntOffset(animatedOffset.toInt(), 0) }
              .alpha(opacity)
              .pointerInput(inlineShareItem?.id) {
                if (inlineShareItem == null) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                      dragOffsetPx =
                          when {
                            abs(dragOffsetPx) > dismissThresholdPx ->
                                if (dragOffsetPx > 0) flingPx else -flingPx
                            else -> 0f
                          }
                    },
                    onHorizontalDrag = { _, dragAmount -> dragOffsetPx += dragAmount },
                )
              },
      shape = RoundedCornerShape(10.dp),
      color = MaterialTheme.colorScheme.warning,
      contentColor = MaterialTheme.colorScheme.onWarning,
  ) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable { viewModel.handleBannerTap(context) }
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
          painter = painterResource(id = iconFor(items)),
          contentDescription = null,
          modifier = Modifier.size(28.dp),
      )
      Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
        Text(
            text = items.title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontSize = 11.sp,
        )
        Text(text = items.subtitle, style = MaterialTheme.typography.bodyMedium)
      }
      if (onlyItem is PendingTaildropItem.File) {
        IconButton(onClick = { viewModel.openTaildropFolder(context) }) {
          Icon(
              painter = painterResource(id = R.drawable.baseline_folder_open_24),
              contentDescription = stringResource(R.string.taildrop_open_folder),
              tint = LocalContentColor.current,
          )
        }
      }
    }
  }
}

private fun iconFor(items: List<PendingTaildropItem>): Int {
  val only = items.singleOrNull() ?: return R.drawable.baseline_drive_folder_upload_24
  return only.icon
}

private val List<PendingTaildropItem>.title: String
  @Composable
  get() {
    val only = singleOrNull() ?: return stringResource(R.string.taildrop_pending_count, size)
    return when (only) {
      is PendingTaildropItem.File -> stringResource(R.string.taildrop_file_received)
      is PendingTaildropItem.InlineShareItem ->
          if (only.share.kind == InlineShare.Kind.URL)
              stringResource(R.string.taildrop_link_received)
          else stringResource(R.string.taildrop_text_received)
    }
  }

private val List<PendingTaildropItem>.subtitle: String
  @Composable
  get() {
    val only = singleOrNull() ?: return stringResource(R.string.taildrop_tap_to_open)
    return when (only) {
      is PendingTaildropItem.File -> stringResource(R.string.taildrop_saved_in_directory)
      is PendingTaildropItem.InlineShareItem ->
          if (only.share.kind == InlineShare.Kind.URL) stringResource(R.string.taildrop_tap_to_open)
          else stringResource(R.string.taildrop_tap_to_copy)
    }
  }
