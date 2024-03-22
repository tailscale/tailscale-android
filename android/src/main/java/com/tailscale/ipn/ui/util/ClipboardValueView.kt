// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.theme.ts_color_light_blue

@Composable
fun ClipboardValueView(
    value: String,
    title: String? = null,
    subtitle: String? = null,
    fontFamily: FontFamily = FontFamily.Monospace
) {
  val localClipboardManager = LocalClipboardManager.current
  Surface(
      color = MaterialTheme.colorScheme.secondaryContainer,
      modifier = Modifier.clip(shape = RoundedCornerShape(8.dp))) {
        Row(
            horizontalArrangement =
                Arrangement.spacedBy(8.dp, alignment = Alignment.CenterHorizontally),
            modifier =
                Modifier.fillMaxWidth()
                    .padding(8.dp)
                    .clickable(onClick = { localClipboardManager.setText(AnnotatedString(value)) }),
            verticalAlignment = Alignment.CenterVertically) {
              Column(
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                  modifier = Modifier.fillMaxWidth().weight(10f)) {
                    title?.let { title ->
                      Text(
                          title,
                          style = MaterialTheme.typography.bodyMedium,
                          fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = fontFamily,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    subtitle?.let { subtitle ->
                      Text(
                          subtitle,
                          style = MaterialTheme.typography.bodyMedium,
                          color = MaterialTheme.colorScheme.secondary)
                    }
                  }
              Icon(
                  painterResource(R.drawable.clipboard),
                  stringResource(R.string.copy_to_clipboard),
                  modifier = Modifier.width(24.dp).height(24.dp),
                  tint = ts_color_light_blue)
            }
      }
}
