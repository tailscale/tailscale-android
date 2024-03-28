// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil.annotation.ExperimentalCoilApi
import coil.compose.AsyncImage
import com.tailscale.ipn.ui.model.IpnLocal

@OptIn(ExperimentalCoilApi::class)
@Composable
fun Avatar(profile: IpnLocal.LoginProfile?, size: Int = 50, action: (() -> Unit)? = null) {
  Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size.dp).clip(CircleShape)) {
    var modifier = Modifier.size((size * .8f).dp)
    action?.let {
      modifier =
          modifier.clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = rememberRipple(bounded = false),
              onClick = action)
    }
    Icon(imageVector = Icons.Default.Person, contentDescription = null, modifier = modifier)

    profile?.UserProfile?.ProfilePicURL?.let { url ->
      AsyncImage(model = url, modifier = Modifier.size((size * 1.2f).dp), contentDescription = null)
    }
  }
}
