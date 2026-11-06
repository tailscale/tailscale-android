// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.components.Header
import com.tailscale.ipn.ui.dataSource.TailnetLockSetupDataSource
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.theme.defaultTextColor
import com.tailscale.ipn.ui.theme.link
import com.tailscale.ipn.ui.util.AndroidTVUtil
import com.tailscale.ipn.ui.util.ClipboardValueView
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.viewModel.StatusItem
import com.tailscale.ipn.ui.viewModel.TailnetLockSetupViewModel
import com.tailscale.ipn.ui.viewModel.TailnetLockSetupViewModelFactory

@Composable
fun TailnetLockSetupView(
    backToSettings: () -> Unit,
    dataSource: TailnetLockSetupDataSource,
) {
  val factory = remember(dataSource) { TailnetLockSetupViewModelFactory(dataSource) }
  val tailnetLockSetupViewModel: TailnetLockSetupViewModel = viewModel(factory = factory)

  val statusItems by tailnetLockSetupViewModel.statusItems.collectAsStateWithLifecycle()
  val nodeKey by tailnetLockSetupViewModel.nodeKey.collectAsStateWithLifecycle()
  val tailnetLockKey by tailnetLockSetupViewModel.tailnetLockKey.collectAsStateWithLifecycle()
  val tailnetLockTlPubKey = tailnetLockKey.replace("nlpub", "tlpub")

  LaunchedEffect(tailnetLockSetupViewModel) {
    LoadingIndicator.start()
    try {
      tailnetLockSetupViewModel.load()
    } finally {
      LoadingIndicator.stop()
    }
  }

  TailnetLockSetupScreen(
      state =
          TailnetLockSetupUiState(
              statusItems = statusItems,
              nodeKey = nodeKey,
              tailnetLockTlPubKey = tailnetLockTlPubKey,
          ),
      onBack = backToSettings,
      isTv = AndroidTVUtil.isAndroidTV(),
  )
}

@Composable
private fun ExplainerView() {
  Lists.MultilineDescription {
    Text(text = explainerText(), style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun explainerText(): AnnotatedString {
  return buildAnnotatedString {
    withStyle(SpanStyle(color = MaterialTheme.colorScheme.defaultTextColor)) {
      append(stringResource(id = R.string.tailnet_lock_explainer))
    }

    pushLink(LinkAnnotation.Url(Links.TAILNET_LOCK_KB_URL))

    withStyle(
        style =
            SpanStyle(
                color = MaterialTheme.colorScheme.link,
                textDecoration = TextDecoration.Underline,
            )
    ) {
      append(stringResource(id = R.string.learn_more))
    }
    pop()
  }
}

private data class TailnetLockSetupUiState(
    val statusItems: List<StatusItem>,
    val nodeKey: String,
    val tailnetLockTlPubKey: String,
)

@Composable
private fun TailnetLockSetupScreen(
    state: TailnetLockSetupUiState,
    onBack: () -> Unit,
    isTv: Boolean,
) {
  Scaffold(
      topBar = {
        Header(
            R.string.tailnet_lock,
            onBack = onBack,
            isTv = isTv,
        )
      }
  ) { innerPadding ->
    LoadingIndicator.Wrap {
      LazyColumn(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
        item { ExplainerView() }

        items(state.statusItems) { statusItem ->
          val interactionSource = remember { MutableInteractionSource() }
          ListItem(
              modifier =
                  Modifier.focusable(interactionSource = interactionSource).clickable(
                      interactionSource = interactionSource,
                      indication = LocalIndication.current,
                  ) {},
              leadingContent = {
                Icon(
                    painter = painterResource(id = statusItem.icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              },
              headlineContent = { Text(stringResource(statusItem.title)) },
          )
        }

        item {
          // Node key section
          Lists.SectionDivider()
          ClipboardValueView(
              value = state.nodeKey,
              title = stringResource(R.string.node_key),
              subtitle = stringResource(R.string.node_key_explainer),
          )

          // Tailnet lock key section
          Lists.SectionDivider()
          ClipboardValueView(
              value = state.tailnetLockTlPubKey,
              title = stringResource(R.string.tailnet_lock_key),
              subtitle = stringResource(R.string.tailnet_lock_key_explainer),
          )
        }
      }
    }
  }
}

@Preview(showSystemUi = true)
@Composable
private fun TailnetLockSetupPreview() {
  AppTheme {
    TailnetLockSetupScreen(
        state =
            TailnetLockSetupUiState(
                statusItems = listOf(),
                nodeKey = "8BADF00D-EA7-1337-DEAD-BEEF",
                tailnetLockTlPubKey = "C0FFEE-CAFE-50DA",
            ),
        onBack = {},
        isTv = false,
    )
  }
}
