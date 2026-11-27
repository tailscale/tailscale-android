// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.Links

@Composable
fun TaildropDirectoryPickerPrompt() {
  val uriHandler = LocalUriHandler.current
  Column(
      verticalArrangement = Arrangement.spacedBy(8.dp),
      horizontalAlignment = Alignment.Start,
  ) {
    Text(text = stringResource(id = R.string.taildrop_directory_picker_body))
    Text(
        text = stringResource(id = R.string.taildrop_directory_picker_info),
        modifier = Modifier.clickable { uriHandler.openUri(Links.TAILDROP_KB_URL) },
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun TaildropDirectoryPickerPromptPreview() {
  TaildropDirectoryPickerPrompt()
}
