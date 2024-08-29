// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.theme.defaultTextColor
import com.tailscale.ipn.ui.theme.link
import com.tailscale.ipn.ui.util.ClipboardValueView
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.TailnetLockSetupViewModel
import com.tailscale.ipn.ui.viewModel.TailnetLockSetupViewModelFactory

@Composable
fun TailnetLockSetupView(
    backToSettings: BackNavigation,
    model: TailnetLockSetupViewModel = viewModel(factory = TailnetLockSetupViewModelFactory())
) {
  val statusItems by model.statusItems.collectAsState()
  val nodeKey by model.nodeKey.collectAsState()
  val tailnetLockKey by model.tailnetLockKey.collectAsState()
  val tailnetLockTlPubKey = tailnetLockKey.replace("nlpub", "tlpub")

  Scaffold(topBar = { Header(R.string.tailnet_lock, onBack = backToSettings) }) { innerPadding ->
    LoadingIndicator.Wrap {
      Column(
          modifier =
              Modifier.padding(innerPadding)
                  .focusable()
                  .verticalScroll(rememberScrollState())
                  .fillMaxSize()) {
            ExplainerView()

            statusItems.forEach { statusItem ->
              Lists.ItemDivider()

              ListItem(
                  leadingContent = {
                    Icon(
                        painter = painterResource(id = statusItem.icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                  },
                  headlineContent = { Text(stringResource(statusItem.title)) })
            }
            // Node key
            Lists.SectionDivider()
            ClipboardValueView(
                value = nodeKey,
                title = stringResource(R.string.node_key),
                subtitle = stringResource(R.string.node_key_explainer))

            // Tailnet lock key
            Lists.SectionDivider()
            ClipboardValueView(
                value = tailnetLockTlPubKey,
                title = stringResource(R.string.tailnet_lock_key),
                subtitle = stringResource(R.string.tailnet_lock_key_explainer))
          }
    }
  }
}

@Composable
private fun ExplainerView() {
  val handler = LocalUriHandler.current

  Lists.MultilineDescription {
    ClickableText(
        explainerText(),
        onClick = { handler.openUri(Links.TAILNET_LOCK_KB_URL) },
        style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
fun explainerText(): AnnotatedString {
  return buildAnnotatedString {
    withStyle(SpanStyle(color = MaterialTheme.colorScheme.defaultTextColor)) {
      append(stringResource(id = R.string.tailnet_lock_explainer))
    }

    pushStringAnnotation(tag = "tailnetLockSupportURL", annotation = Links.TAILNET_LOCK_KB_URL)

    withStyle(
        style =
            SpanStyle(
                color = MaterialTheme.colorScheme.link,
                textDecoration = TextDecoration.Underline)) {
          append(stringResource(id = R.string.learn_more))
        }
    pop()
  }
}

@Composable
@Preview
fun TailnetLockSetupViewPreview() {
  val vm = TailnetLockSetupViewModel()
  vm.nodeKey.set("8BADF00D-EA7-1337-DEAD-BEEF")
  vm.tailnetLockKey.set("C0FFEE-CAFE-50DA")
  TailnetLockSetupView(backToSettings = {}, vm)
}
