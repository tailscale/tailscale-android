// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.theme.ts_color_light_blue
import com.tailscale.ipn.ui.util.defaultPaddingModifier
import com.tailscale.ipn.ui.util.settingsRowModifier
import com.tailscale.ipn.ui.viewModel.BugReportViewModel
import kotlinx.coroutines.flow.StateFlow

@Composable
fun BugReportView(nav: BackNavigation, model: BugReportViewModel = viewModel()) {
  val handler = LocalUriHandler.current

  Scaffold(topBar = { Header(R.string.bug_report_title, onBack = nav.onBack) }) { innerPadding ->
    Column(
        modifier = Modifier.padding(innerPadding).padding(24.dp).fillMaxWidth().fillMaxHeight()) {
          ClickableText(
              text = contactText(),
              modifier = Modifier.fillMaxWidth(),
              style = MaterialTheme.typography.bodyMedium,
              onClick = { handler.openUri(Links.SUPPORT_URL) })

          Spacer(modifier = Modifier.height(16.dp))

          ReportIdRow(bugReportIdFlow = model.bugReportID)

          Spacer(modifier = Modifier.height(16.dp))

          Text(
              text = stringResource(id = R.string.bug_report_id_desc),
              modifier = Modifier.fillMaxWidth(),
              textAlign = TextAlign.Left,
              color = MaterialTheme.colorScheme.secondary,
              style = MaterialTheme.typography.bodySmall)
        }
  }
}

@Composable
fun ReportIdRow(bugReportIdFlow: StateFlow<String>) {
  val localClipboardManager = LocalClipboardManager.current
  val bugReportId = bugReportIdFlow.collectAsState()

  Row(
      modifier =
          settingsRowModifier()
              .fillMaxWidth()
              .clickable(
                  onClick = { localClipboardManager.setText(AnnotatedString(bugReportId.value)) }),
      verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(10f)) {
          Text(
              text = bugReportId.value,
              style = MaterialTheme.typography.titleMedium,
              fontFamily = FontFamily.Monospace,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = defaultPaddingModifier())
        }
        Box(Modifier.weight(1f)) {
          Icon(Icons.Outlined.Share, null, modifier = Modifier.width(24.dp).height(24.dp))
        }
      }
}

@Composable
fun contactText(): AnnotatedString {
  val annotatedString = buildAnnotatedString {
    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) {
      append(stringResource(id = R.string.bug_report_instructions_prefix))
    }

    pushStringAnnotation(tag = "reportLink", annotation = Links.SUPPORT_URL)
    withStyle(
        style = SpanStyle(color = ts_color_light_blue, textDecoration = TextDecoration.Underline)) {
          append(stringResource(id = R.string.bug_report_instructions_linktext))
        }
    pop()

    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) {
      append(stringResource(id = R.string.bug_report_instructions_suffix))
    }
  }
  return annotatedString
}
