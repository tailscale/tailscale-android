// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R

@Composable
fun IntroView(onContinue: () -> Unit) {
  Column(
      modifier = Modifier.fillMaxHeight().fillMaxWidth().verticalScroll(rememberScrollState()),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center) {
        Image(
            modifier = Modifier.width(80.dp).height(80.dp),
            painter = painterResource(id = R.drawable.androidicon_light),
            contentDescription = stringResource(R.string.app_icon_content_description))
        Spacer(modifier = Modifier.height(40.dp))
        Text(
            modifier = Modifier.padding(start = 40.dp, end = 40.dp, bottom = 40.dp),
            text = stringResource(R.string.welcome1),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center)

        Button(onClick = onContinue) {
          Text(
              text = stringResource(id = R.string.getStarted),
              fontSize = MaterialTheme.typography.titleMedium.fontSize)
        }
        Spacer(modifier = Modifier.height(40.dp))

        Box(
            modifier = Modifier.fillMaxHeight().padding(start = 20.dp, end = 20.dp, bottom = 40.dp),
            contentAlignment = Alignment.BottomCenter) {
              Text(
                  text = stringResource(R.string.welcome2),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center)
            }
      }
}
