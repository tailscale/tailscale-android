// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links

@Composable
fun AboutView(backToSettings: BackNavigation) {
  Scaffold(topBar = { Header(R.string.about_view_title, onBack = backToSettings) }) { innerPadding
    ->
    Column(
        verticalArrangement =
            Arrangement.spacedBy(space = 20.dp, alignment = Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(innerPadding).verticalScroll(rememberScrollState())) {
          Image(
              modifier =
                  Modifier.width(100.dp)
                      .height(100.dp)
                      .clip(RoundedCornerShape(50))
                      .background(MaterialTheme.colorScheme.onSurface)
                      .padding(15.dp),
              painter = painterResource(id = R.drawable.androidicon),
              contentDescription = stringResource(R.string.app_icon_content_description))

          Column(
              verticalArrangement =
                  Arrangement.spacedBy(space = 2.dp, alignment = Alignment.CenterVertically),
              horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.about_view_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = MaterialTheme.typography.titleLarge.fontSize)
                Text(
                    text = "${stringResource(R.string.version)} ${BuildConfig.VERSION_NAME}",
                    fontWeight = MaterialTheme.typography.bodyMedium.fontWeight,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize)
              }

          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            OpenURLButton(stringResource(R.string.acknowledgements), Links.LICENSES_URL)
            OpenURLButton(stringResource(R.string.privacy_policy), Links.PRIVACY_POLICY_URL)
            OpenURLButton(stringResource(R.string.terms_of_service), Links.TERMS_URL)
          }

          Text(
              stringResource(R.string.about_view_footnotes),
              fontWeight = FontWeight.Normal,
              fontSize = MaterialTheme.typography.labelMedium.fontSize,
              textAlign = TextAlign.Center)
        }
  }
}
