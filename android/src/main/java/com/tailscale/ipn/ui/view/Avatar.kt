// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.annotation.ExperimentalCoilApi
import coil.compose.AsyncImage
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.util.AndroidTVUtil
import com.tailscale.ipn.ui.util.conditional

@OptIn(ExperimentalCoilApi::class)
@Composable
fun Avatar(
    profile: IpnLocal.LoginProfile?,
    size: Int = 50,
    action: (() -> Unit)? = null,
    isFocusable: Boolean = false
) {
  val isFocused = remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current

  // Outer Box for the larger focusable and clickable area
  Box(
      contentAlignment = Alignment.Center,
      modifier =
          Modifier.conditional(AndroidTVUtil.isAndroidTV(), { padding(4.dp) })
              .conditional(
                  AndroidTVUtil.isAndroidTV() && isFocusable,
                  {
                    size((size * 1.5f).dp) // Focusable area is larger than the avatar
                  })
              .clip(CircleShape) // Ensure both the focus and click area are circular
              .background(
                  if (isFocused.value) MaterialTheme.colorScheme.surface else Color.Transparent,
              )
              .onFocusChanged { focusState -> isFocused.value = focusState.isFocused }
              .focusable() // Make this outer Box focusable (after onFocusChanged)
              .clickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = ripple(bounded = true), // Apply ripple effect inside circular bounds
                  onClick = {
                    action?.invoke()
                    focusManager.clearFocus() // Clear focus after clicking the avatar
                  })) {
        // Inner Box to hold the avatar content (Icon or AsyncImage)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size.dp).clip(CircleShape)) {
              // Always display the default icon as a background layer
              Icon(
                  imageVector = Icons.Default.Person,
                  contentDescription = stringResource(R.string.settings_title),
                  modifier =
                      Modifier.conditional(AndroidTVUtil.isAndroidTV(), { size((size * 0.8f).dp) })
                          .clip(CircleShape) // Icon size slightly smaller than the Box
                  )

              // Overlay the profile picture if available
              profile?.UserProfile?.ProfilePicURL?.let { url ->
                AsyncImage(
                    model = url,
                    modifier = Modifier.size(size.dp).clip(CircleShape),
                    contentDescription = null)
              }
            }
      }
}
