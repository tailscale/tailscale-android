// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.UserSwitcherViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSwitcherView( 
    nav: BackNavigation,
    onNavigateHome: () -> Unit,
    viewModel: UserSwitcherViewModel = viewModel()
) {

  val users = viewModel.loginProfiles.collectAsState().value
  val currentUser = viewModel.loggedInUser.collectAsState().value

  Scaffold(topBar = { Header(R.string.accounts, onBack = nav.onBack) }) { innerPadding ->
    Column(
        modifier = Modifier.padding(innerPadding).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
          val showDialog = viewModel.showDialog.collectAsState().value

          // Show the error overlay if need be
          showDialog?.let { ErrorDialog(type = it, action = { viewModel.showDialog.set(null) }) }

          // When switch is invoked, this stores the ID of the user we're trying to switch to
          // so we can decorate it with a spinner.  The actual logged in user will not change
          // until
          // we get our first netmap update back with the new userId for SelfNode.
          // (jonathan) TODO: This user switch is not immediate.  We may need to represent the
          // "switching users" state globally (if ipnState is insufficient)
          val nextUserId = remember { mutableStateOf<String?>(null) }

          LazyColumn {
            itemsWithDividers(users ?: emptyList()) { user ->
              if (user.ID == currentUser?.ID) {
                UserView(profile = user, actionState = UserActionState.CURRENT)
              } else {
                val state =
                    if (user.ID == nextUserId.value) UserActionState.SWITCHING
                    else UserActionState.NONE
                UserView(
                    profile = user,
                    actionState = state,
                    onClick = {
                      nextUserId.value = user.ID
                      viewModel.switchProfile(user) {
                        if (it.isFailure) {
                          viewModel.showDialog.set(ErrorDialogType.LOGOUT_FAILED)
                          nextUserId.value = null
                        } else {
                          onNavigateHome()
                        }
                      }
                    })
              }
            }

            item {
              Lists.SectionDivider()
              SettingRow(viewModel.addProfileSetting)
              Lists.ItemDivider()
              SettingRow(viewModel.loginSetting)
              if (currentUser != null){
                Lists.ItemDivider()
                SettingRow(viewModel.logoutSetting)
              }
            }
          }
        }
  }
}
