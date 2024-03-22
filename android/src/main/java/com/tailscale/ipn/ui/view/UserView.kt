// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.util.defaultPaddingModifier
import com.tailscale.ipn.ui.util.settingsRowModifier

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
  Column {
    Row(
        modifier = settingsRowModifier().clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically) {
          profile?.let {
            Box(modifier = defaultPaddingModifier()) { Avatar(profile = profile, size = 36) }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
              Text(
                  text = profile.UserProfile.DisplayName,
                  style = MaterialTheme.typography.titleMedium)
              Text(text = profile.Name, style = MaterialTheme.typography.bodyMedium)
            }
          }
              ?: run {
                Box(modifier = Modifier.weight(1f)) {
                  Text(
                      text = stringResource(id = R.string.accounts),
                      style = MaterialTheme.typography.titleMedium)
                }
              }

          when (actionState) {
            UserActionState.CURRENT -> CheckedIndicator()
            UserActionState.SWITCHING -> SimpleActivityIndicator(size = 26)
            UserActionState.NAV -> ChevronRight()
            UserActionState.NONE -> Unit
          }
        }
  }
}
