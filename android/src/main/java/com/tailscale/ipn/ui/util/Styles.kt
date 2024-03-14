// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R

class ComposableStringFormatter(@StringRes val stringRes: Int = R.string.template, private vararg val params: Any) {
    constructor(string: String) : this(stringRes = R.string.template, string)

    @Composable
    fun getString(): String = stringResource(id = stringRes, *params)
}


@Composable
fun settingsRowModifier(): Modifier {
    return Modifier
            .clip(shape = RoundedCornerShape(8.dp))
            .background(color = MaterialTheme.colorScheme.secondaryContainer)
            .fillMaxWidth()
}

@Composable
fun defaultPaddingModifier(): Modifier {
    return Modifier.padding(8.dp)
}