// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R

@Composable
fun IntroView(onContinue: () -> Unit) {
  Surface {
    Column(
        modifier = Modifier.fillMaxHeight().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
          Spacer(modifier = Modifier.height(30.dp))
          Image(
              modifier =
                  Modifier.width(140.dp)
                      .height(140.dp)
                      .clip(RoundedCornerShape(50))
                      .background(Color.Black)
                      .padding(15.dp),
              painter = painterResource(id = R.drawable.ic_tile),
              contentDescription = stringResource(R.string.app_icon_content_description))
          Spacer(modifier = Modifier.height(10.dp))
          Text(
              text = stringResource(R.string.tailscale),
              style = MaterialTheme.typography.headlineLarge)
          Spacer(modifier = Modifier.height(20.dp))
          Text(
              text = stringResource(R.string.welcome1),
              style = MaterialTheme.typography.bodyMedium,
              textAlign = TextAlign.Center)
          Spacer(modifier = Modifier.height(20.dp))
          PrimaryActionButton(onClick = onContinue) {
            Text(
                text = stringResource(id = R.string.getStarted),
                fontSize = MaterialTheme.typography.titleMedium.fontSize)
          }
        }
  }
}
