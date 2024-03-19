// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.ui.theme.ts_color_light_blue

@Composable
fun PrimaryActionButton(
        onClick: () -> Unit,
        content: @Composable RowScope.() -> Unit
) {
    Button(
            onClick = onClick,
            colors = ButtonColors(
                    containerColor = ts_color_light_blue,
                    contentColor = Color.White,
                    disabledContainerColor = MaterialTheme.colorScheme.secondary,
                    disabledContentColor = MaterialTheme.colorScheme.onSecondary
            ),
            contentPadding = PaddingValues(vertical = 12.dp),
            modifier = Modifier
                    .fillMaxWidth(),
            content = content
    )
}

@Composable
fun OpenURLButton(title: String, url: String) {
    val handler = LocalUriHandler.current

    Button(
            onClick = { handler.openUri(url) },
            content = {
                Text(title)
            },
            colors = ButtonDefaults.buttonColors(
                    contentColor = MaterialTheme.colorScheme.secondary,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
    )
}
