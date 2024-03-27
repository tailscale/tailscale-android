// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.theme.ts_color_light_blue
import com.tailscale.ipn.ui.util.ClipboardValueView
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.viewModel.TailnetLockSetupViewModel
import com.tailscale.ipn.ui.viewModel.TailnetLockSetupViewModelFactory

@Composable
fun TailnetLockSetupView(
    nav: BackNavigation,
    model: TailnetLockSetupViewModel = viewModel(factory = TailnetLockSetupViewModelFactory())
) {
  val statusItems = model.statusItems.collectAsState().value
  val nodeKey = model.nodeKey.collectAsState().value
  val tailnetLockKey = model.tailnetLockKey.collectAsState().value

  Scaffold(topBar = { Header(R.string.tailnet_lock, onBack = nav.onBack) }) { innerPadding ->
    LoadingIndicator.Wrap {
      LazyColumn(modifier = Modifier.padding(innerPadding)) {
        item(key = "header") {
          ExplainerView()
          Spacer(Modifier.size(4.dp))
        }

        items(items = statusItems, key = { "status_${it.title}" }) { statusItem ->
          ListItem(
              leadingContent = {
                Icon(
                    painter = painterResource(id = statusItem.icon),
                    contentDescription = null,
                    tint = ts_color_light_blue)
              },
              headlineContent = { Text(stringResource(statusItem.title)) })
        }

        item(key = "nodeKey") {
          Lists.SectionDivider()

          ClipboardValueView(
              value = nodeKey,
              title = stringResource(R.string.node_key),
              subtitle = stringResource(R.string.node_key_explainer))
        }

        item(key = "tailnetLockKey") {
          ClipboardValueView(
              value = tailnetLockKey,
              title = stringResource(R.string.tailnet_lock_key),
              subtitle = stringResource(R.string.tailnet_lock_key_explainer))
        }
      }
    }
  }
}

@Composable
private fun ExplainerView() {
  val handler = LocalUriHandler.current

  ClickableText(
      explainerText(),
      modifier = Modifier.padding(16.dp),
      onClick = { handler.openUri(Links.TAILNET_LOCK_KB_URL) })
}

@Composable
fun explainerText(): AnnotatedString {
  val annotatedString = buildAnnotatedString {
    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) {
      append(stringResource(id = R.string.tailnet_lock_explainer))
    }

    pushStringAnnotation(tag = "tailnetLockSupportURL", annotation = Links.TAILNET_LOCK_KB_URL)
    withStyle(
        style = SpanStyle(color = ts_color_light_blue, textDecoration = TextDecoration.Underline)) {
          append(stringResource(id = R.string.learn_more))
        }
    pop()
  }
  return annotatedString
}
