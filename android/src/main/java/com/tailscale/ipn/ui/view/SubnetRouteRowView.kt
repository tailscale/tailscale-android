// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R

/**
 * SubnetRouteRowView is a row in RunSubnetRouterView, representing a subnet route.
 * It provides options to edit or delete the route.
 *
 * @param route The subnet route itself (e.g., "192.168.1.0/24").
 * @param onEdit A callback invoked when the edit icon is clicked.
 * @param onDelete A callback invoked when the delete icon is clicked.
 */
@Composable
fun SubnetRouteRowView(
    route: String, onEdit: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier
) {
    ListItem(
        headlineContent = { Text(text = route, style = MaterialTheme.typography.bodyMedium) },
        trailingContent = {
            Row {
                IconButton(onClick = onEdit) {
                    Icon(
                        painterResource(R.drawable.pencil),
                        contentDescription = stringResource(R.string.edit_route),
                        modifier = Modifier.size(24.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(
                        painterResource(R.drawable.xmark),
                        contentDescription = stringResource(R.string.delete_route),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        modifier = modifier
    )
}