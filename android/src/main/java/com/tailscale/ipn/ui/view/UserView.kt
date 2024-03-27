// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.IpnLocal

// Used to decorate UserViews.
// NONE indicates no decoration
// CURRENT indicates the user is the current user and will be "checked"
// SWITCHING indicates the user is being switched to and will be "loading"
// NAV will show a chevron
enum class UserActionState {
  CURRENT,
  SWITCHING,
  NAV,
  NONE
}

@Composable
fun UserView(
    profile: IpnLocal.LoginProfile?,
    onClick: () -> Unit = {},
    actionState: UserActionState = UserActionState.NONE
) {
  Box {
    profile?.let {
      ListItem(
          modifier = Modifier.clickable { onClick() },
          leadingContent = { Avatar(profile = profile, size = 36) },
          headlineContent = {
            Text(
                text = profile.UserProfile.DisplayName,
                style = MaterialTheme.typography.titleMedium)
          },
          supportingContent = {
            Text(text = profile.Name, style = MaterialTheme.typography.bodyMedium)
          },
      )
    }
        ?: run {
          ListItem(
              modifier = Modifier.clickable { onClick() },
              headlineContent = {
                Text(
                    text = stringResource(id = R.string.accounts),
                    style = MaterialTheme.typography.titleMedium)
              })
        }
  }
}
