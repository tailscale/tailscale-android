// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.theme.link
import com.tailscale.ipn.ui.util.ClipboardValueView
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.viewModel.TailnetLockSetupViewModel
import com.tailscale.ipn.ui.viewModel.TailnetLockSetupViewModelFactory

@Composable
fun TailnetLockSetupView(
    backToSettings: BackNavigation,
    model: TailnetLockSetupViewModel = viewModel(factory = TailnetLockSetupViewModelFactory())
) {
  val statusItems = model.statusItems.collectAsState().value
  val nodeKey = model.nodeKey.collectAsState().value
  val tailnetLockKey = model.tailnetLockKey.collectAsState().value

  Scaffold(topBar = { Header(R.string.tailnet_lock, onBack = backToSettings) }) { innerPadding ->
    LoadingIndicator.Wrap {
      LazyColumn(modifier = Modifier.padding(innerPadding)) {
        item(key = "header") { ExplainerView() }

        items(items = statusItems, key = { "status_${it.title}" }) { statusItem ->
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

        item(key = "nodeKey") {
          Lists.SectionDivider()

          ClipboardValueView(
              value = nodeKey,
              title = stringResource(R.string.node_key),
              subtitle = stringResource(R.string.node_key_explainer))
        }

        item(key = "tailnetLockKey") {
          Lists.SectionDivider()

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

  Lists.MultilineDescription {
    ClickableText(
        explainerText(),
        onClick = { handler.openUri(Links.TAILNET_LOCK_KB_URL) },
        style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
fun explainerText(): AnnotatedString {
  val annotatedString = buildAnnotatedString {
    append(stringResource(id = R.string.tailnet_lock_explainer))

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
  return annotatedString
}
