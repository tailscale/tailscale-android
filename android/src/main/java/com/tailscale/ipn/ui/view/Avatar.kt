// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil.annotation.ExperimentalCoilApi
import coil.compose.rememberImagePainter
import com.tailscale.ipn.ui.model.IpnLocal

@OptIn(ExperimentalCoilApi::class)
@Composable
fun Avatar(profile: IpnLocal.LoginProfile?, size: Int = 50) {
  Box(
      contentAlignment = Alignment.Center,
      modifier =
          Modifier.size(size.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.tertiaryContainer)) {
        profile?.UserProfile?.ProfilePicURL?.let { url ->
          val painter = rememberImagePainter(data = url)
          Image(painter = painter, contentDescription = null, modifier = Modifier.size(size.dp))
        }
            ?: run {
              Icon(
                  imageVector = Icons.Default.Person,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onTertiaryContainer,
                  modifier = Modifier.size((size * .8f).dp))
            }
      }
}
