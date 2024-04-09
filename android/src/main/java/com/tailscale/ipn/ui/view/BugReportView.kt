// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
import com.tailscale.ipn.ui.viewModel.BugReportViewModel

@Composable
fun BugReportView(backToSettings: BackNavigation, model: BugReportViewModel = viewModel()) {
  val handler = LocalUriHandler.current
  val bugReportID = model.bugReportID.collectAsState().value

  Scaffold(topBar = { Header(R.string.bug_report_title, onBack = backToSettings) }) { innerPadding
    ->
    Column(
        modifier =
            Modifier.padding(innerPadding)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())) {
          Lists.MultilineDescription {
            ClickableText(
                text = contactText(),
                style = MaterialTheme.typography.bodyMedium,
                onClick = { handler.openUri(Links.SUPPORT_URL) })
          }

          ClipboardValueView(bugReportID, title = stringResource(R.string.bug_report_id))

          Lists.InfoItem(stringResource(id = R.string.bug_report_id_desc))
        }
  }
}

@Composable
fun contactText(): AnnotatedString {
  val annotatedString = buildAnnotatedString {
    append(stringResource(id = R.string.bug_report_instructions_prefix))

    pushStringAnnotation(tag = "reportLink", annotation = Links.SUPPORT_URL)
    withStyle(
        style =
            SpanStyle(
                color = MaterialTheme.colorScheme.link,
                textDecoration = TextDecoration.Underline)) {
          append(stringResource(id = R.string.bug_report_instructions_linktext))
        }
    pop()

    append(stringResource(id = R.string.bug_report_instructions_suffix))
  }
  return annotatedString
}
