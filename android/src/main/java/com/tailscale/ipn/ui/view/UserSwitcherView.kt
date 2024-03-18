// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.Header
import com.tailscale.ipn.ui.util.defaultPaddingModifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.util.settingsRowModifier
import com.tailscale.ipn.ui.viewModel.UserSwitcherViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSwitcherView(viewModel: UserSwitcherViewModel = viewModel()) {

    val users = viewModel.loginProfiles.collectAsState().value
    val currentUser = viewModel.loggedInUser.collectAsState().value

    Surface(modifier = Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = defaultPaddingModifier().fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val showDialog = viewModel.showDialog.collectAsState().value

            // Show the error overlay if need be
            showDialog?.let { ErrorDialog(type = it, action = { viewModel.showDialog.set(null) }) }

            Header(title = R.string.accounts)

            Column(modifier = settingsRowModifier()) {

                // When switch is invoked, this stores the ID of the user we're trying to switch to
                // so we can decorate it with a spinner.  The actual logged in user will not change until
                // we get our first netmap update back with the new userId for SelfNode.
                // (jonathan) TODO: This user switch is not immediate.  We may need to represent the
                // "switching users" state globally (if ipnState is insufficient)
                val nextUserId = remember { mutableStateOf<String?>(null) }

                users?.forEach { user ->
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
                                        }
                                    }
                                })
                    }
                }
                SettingRow(viewModel.addProfileSetting)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column(modifier = settingsRowModifier()) {
                SettingRow(viewModel.loginSetting)
                SettingRow(viewModel.logoutSetting)
            }
        }
    }
}
