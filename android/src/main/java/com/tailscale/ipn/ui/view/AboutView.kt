// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.theme.logoBackground
import com.tailscale.ipn.ui.util.AppVersion

@Composable
fun AboutView(backToSettings: BackNavigation) {
  val localClipboardManager = LocalClipboardManager.current

  Scaffold(topBar = { Header(R.string.about_view_header, onBack = backToSettings) }) { innerPadding
    ->
    Column(
        verticalArrangement =
            Arrangement.spacedBy(space = 20.dp, alignment = Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier.fillMaxWidth()
                .fillMaxHeight()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())) {
          TailscaleLogoView(
              usesOnBackgroundColors = true,
              modifier =
                  Modifier.width(100.dp)
                      .height(100.dp)
                      .clip(RoundedCornerShape(50))
                      .background(MaterialTheme.colorScheme.logoBackground)
                      .padding(25.dp))

          Column(
              verticalArrangement =
                  Arrangement.spacedBy(space = 2.dp, alignment = Alignment.CenterVertically),
              horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.about_view_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = MaterialTheme.typography.titleLarge.fontSize)
                Text(
                    modifier =
                        Modifier.clickable {
                          // When users tap on the version number, the extended version string
                          // (including commit hashes) is copied to the clipboard.
                          // This may be useful for debugging purposes...
                          localClipboardManager.setText(AnnotatedString(BuildConfig.VERSION_NAME))
                        },
                    // ... but we always display the short version in the UI to avoid user
                    // confusion.
                    text = "${stringResource(R.string.version)} ${AppVersion.Short()}",
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

@Preview
@Composable
fun AboutPreview() {
  AboutView({})
}
