// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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
  val showHeaderMenu = viewModel.showHeaderMenu.collectAsState().value

  Scaffold(
      topBar = {
        Header(
            R.string.accounts,
            onBack = nav.onBack,
            actions = {
              Row {
                FusMenu(viewModel = viewModel)
                IconButton(onClick = { viewModel.showHeaderMenu.set(!showHeaderMenu) }) {
                  Icon(Icons.Default.MoreVert, "menu")
                }
              }
            })
      }) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
              val showErrorDialog = viewModel.errorDialog.collectAsState().value

              // Show the error overlay if need be
              showErrorDialog?.let {
                ErrorDialog(type = it, action = { viewModel.errorDialog.set(null) })
              }

              // When switch is invoked, this stores the ID of the user we're trying to switch to
              // so we can decorate it with a spinner.  The actual logged in user will get updated
              // as soon as we switch states.
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
                              viewModel.errorDialog.set(ErrorDialogType.LOGOUT_FAILED)
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
                  if (currentUser != null) {
                    Lists.ItemDivider()
                    SettingRow(viewModel.logoutSetting)
                  }
                }
              }
            }
      }
}

@Composable
fun FusMenu(viewModel: UserSwitcherViewModel) {
  var url by remember { mutableStateOf("") }
  val expanded = viewModel.showHeaderMenu.collectAsState().value

  DropdownMenu(
      expanded = expanded,
      onDismissRequest = {
        url = ""
        viewModel.showHeaderMenu.set(false)
      },
      modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        DropdownMenuItem(
            onClick = {},
            text = {
              Column {
                Text(
                    stringResource(id = R.string.custom_control_menu),
                    style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.padding(2.dp))
                Text(
                    stringResource(id = R.string.custom_control_menu_desc),
                    style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.padding(8.dp))

                OutlinedTextField(
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    value = url,
                    onValueChange = { url = it },
                    placeholder = {
                      Text(
                          stringResource(id = R.string.custom_control_placeholder),
                          style = MaterialTheme.typography.bodySmall)
                    })

                Spacer(modifier = Modifier.padding(8.dp))

                FullWidthButton(onClick = { viewModel.setControlURL(url) }) {
                  Text(stringResource(id = R.string.add_account_short))
                }
              }
            })
      }
}
