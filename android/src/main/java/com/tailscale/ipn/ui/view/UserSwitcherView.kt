// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.components.DropdownMenuItem
import com.tailscale.ipn.ui.components.Header
import com.tailscale.ipn.ui.components.ListItemText
import com.tailscale.ipn.ui.dataSource.LoginActionsDataSource
import com.tailscale.ipn.ui.dataSource.UserSwitcherDataSource
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.AndroidTVUtil
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.UserSwitcherNav
import com.tailscale.ipn.ui.viewModel.UserSwitcherViewModel
import com.tailscale.ipn.ui.viewModel.UserSwitcherViewModelFactory
import kotlin.Boolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSwitcherView(
    userSwitcherNav: UserSwitcherNav,
    dataSource: UserSwitcherDataSource,
    loginActions: LoginActionsDataSource,
) {
  val context = LocalContext.current

  val factory =
      remember(dataSource, loginActions) { UserSwitcherViewModelFactory(dataSource, loginActions) }
  val userSwitcherViewModel: UserSwitcherViewModel = viewModel(factory = factory)

  val users by userSwitcherViewModel.loginProfiles.collectAsStateWithLifecycle()
  val currentUser by userSwitcherViewModel.loggedInUser.collectAsStateWithLifecycle()
  val showHeaderMenu by userSwitcherViewModel.showHeaderMenu.collectAsStateWithLifecycle()
  val netmapState by userSwitcherViewModel.netmap.collectAsStateWithLifecycle()
  val showErrorDialog by userSwitcherViewModel.errorDialog.collectAsStateWithLifecycle()

  var showDeleteDialog by remember { mutableStateOf(false) }
  val capabilityIsOwner = "https://tailscale.com/cap/is-owner"
  val isOwner = netmapState?.hasCap(capabilityIsOwner) == true

  // When switch is invoked, this stores the ID of the user we're trying to switch to
  // so we can decorate it with a spinner.  The actual logged in user will not change
  // until we get our first netmap update back with the new userId for SelfNode.
  // (jonathan) TODO: This user switch is not immediate.  We may need to represent the
  // "switching users" state globally (if ipnState is insufficient)
  var nextUserId by remember { mutableStateOf<String?>(null) }

  UserSwitcherScreen(
      state =
          UserSwitcherUiState(
              showHeaderMenu = showHeaderMenu,
              showErrorDialog = showErrorDialog,
              users = users.orEmpty(),
              currentUser = currentUser,
              nextUserId = nextUserId,
          ),
      toggleHeaderMenu = { userSwitcherViewModel.setHeaderMenuExpanded(it) },
      switchProfile = { profile ->
        nextUserId = profile.ID
        userSwitcherViewModel.switchProfile(profile) { result ->
          if (result.isFailure) {
            userSwitcherViewModel.showErrorDialog(ErrorDialogType.LOGOUT_FAILED)
            nextUserId = null
          } else {
            userSwitcherNav.onNavigateHome()
          }
        }
      },
      addProfile = {
        userSwitcherViewModel.addProfile { result ->
          if (result.isFailure) {
            userSwitcherViewModel.showErrorDialog(ErrorDialogType.ADD_PROFILE_FAILED)
          }
        }
      },
      logout = {
        userSwitcherViewModel.logout { result ->
          result
              .onSuccess { userSwitcherNav.onNavigateHome() }
              .onFailure { userSwitcherViewModel.showErrorDialog(ErrorDialogType.LOGOUT_FAILED) }
        }
      },
      login = { userSwitcherViewModel.login() },
      showDeleteDialog = { showDeleteDialog = true },
      nav = userSwitcherNav,
      isTv = AndroidTVUtil.isAndroidTV(),
  )

  // Show the error overlay if need be
  showErrorDialog?.let {
    ErrorDialog(type = it, action = { userSwitcherViewModel.clearErrorDialog() })
  }

  if (showDeleteDialog) {
    AlertDialog(
        onDismissRequest = { showDeleteDialog = false },
        title = { Text(text = stringResource(R.string.delete_tailnet)) },
        text = {
          if (isOwner) {
            OwnerDeleteDialogText()
          } else {
            Text(stringResource(R.string.request_deletion_nonowner))
          }
        },
        confirmButton = {
          TextButton(
              onClick = {
                val intent =
                    Intent(Intent.ACTION_VIEW, "https://tailscale.com/contact/support".toUri())
                context.startActivity(intent)
                showDeleteDialog = false
              }
          ) {
            Text(text = stringResource(R.string.contact_support))
          }
        },
        dismissButton = {
          TextButton(onClick = { showDeleteDialog = false }) {
            Text(text = stringResource(R.string.cancel))
          }
        },
    )
  }
}

@Composable
private fun FusMenu(
    expanded: Boolean,
    onCustomClick: () -> Unit,
    onAuthKeyClick: () -> Unit,
    hideHeaderMenu: () -> Unit,
) {
  DropdownMenu(
      expanded = expanded,
      onDismissRequest = hideHeaderMenu,
      modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
  ) {
    DropdownMenuItem(
        onClick = {
          onCustomClick()
          hideHeaderMenu()
        },
        text = stringResource(id = R.string.custom_control_menu),
    )
    DropdownMenuItem(
        onClick = {
          onAuthKeyClick()
          hideHeaderMenu()
        },
        text = stringResource(id = R.string.auth_key_menu),
    )
  }
}

@Composable
private fun OwnerDeleteDialogText() {
  val part1 = stringResource(R.string.request_deletion_owner_part1)
  val part2a = stringResource(R.string.request_deletion_owner_part2a)
  val part2b = stringResource(R.string.request_deletion_owner_part2b)

  val annotatedText = buildAnnotatedString {
    append("$part1 ")

    pushLink(LinkAnnotation.Url("https://login.tailscale.com/admin/settings/general"))
    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) {
      append("Settings > General")
    }
    pop()

    append(" $part2a\n\n") // newline after "Delete tailnet."
    append(part2b)
  }

  Text(
      text = annotatedText,
      style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
  )
}

private data class UserSwitcherUiState(
    val showHeaderMenu: Boolean,
    val showErrorDialog: ErrorDialogType?,
    val users: List<IpnLocal.LoginProfile>,
    val currentUser: IpnLocal.LoginProfile?,
    val nextUserId: String?,
)

@Composable
private fun UserSwitcherScreen(
    state: UserSwitcherUiState,
    toggleHeaderMenu: (Boolean) -> Unit,
    switchProfile: (IpnLocal.LoginProfile) -> Unit,
    addProfile: () -> Unit,
    logout: () -> Unit,
    login: () -> Unit,
    showDeleteDialog: () -> Unit,
    nav: UserSwitcherNav,
    isTv: Boolean,
) {
  Scaffold(
      topBar = {
        Header(
            R.string.accounts,
            onBack = nav.backToSettings,
            isTv = isTv,
            actions = {
              Row {
                FusMenu(
                    expanded = state.showHeaderMenu,
                    onAuthKeyClick = nav.onNavigateToAuthKey,
                    onCustomClick = nav.onNavigateCustomControl,
                    hideHeaderMenu = { toggleHeaderMenu(false) },
                )
                IconButton(onClick = { toggleHeaderMenu(!state.showHeaderMenu) }) {
                  Icon(Icons.Default.MoreVert, "menu")
                }
              }
            },
        )
      }
  ) { innerPadding ->
    Column(
        modifier = Modifier.padding(innerPadding).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      LazyColumn {
        itemsWithDividers(state.users) { user ->
          if (user.ID == state.currentUser?.ID) {
            UserView(profile = user, isTv = isTv, actionState = UserActionState.CURRENT)
          } else {
            val state =
                if (user.ID == state.nextUserId) UserActionState.SWITCHING else UserActionState.NONE
            UserView(
                profile = user,
                isTv = isTv,
                actionState = state,
                onClick = { switchProfile(user) },
            )
          }
        }

        item {
          Lists.SectionDivider()
          ListItemText(R.string.add_account, onClick = addProfile)

          Lists.ItemDivider()
          ListItemText(R.string.reauthenticate, onClick = login)

          if (state.currentUser != null) {
            Lists.ItemDivider()
            ListItemText(
                R.string.log_out,
                destructive = true,
                onClick = logout,
            )
          }

          Lists.SectionDivider()
          ListItemText(
              R.string.delete_tailnet,
              destructive = true,
              onClick = showDeleteDialog,
          )
        }
      }
    }
  }
}

@Preview(showSystemUi = true)
@Composable
private fun UserSwitcherViewPreview() {
  val user1 =
      IpnLocal.LoginProfile(
          ID = "1",
          Name = "User 1",
          Key = "123",
          UserProfile =
              Tailcfg.UserProfile(
                  DisplayName = "Person 1",
                  LoginName = "Person 1",
              ),
          NetworkProfile = Tailcfg.NetworkProfile(DomainName = "tailscale.com"),
          LocalUserID = "1",
          ControlURL = null,
      )
  val user2 =
      IpnLocal.LoginProfile(
          ID = "2",
          Name = "User 2",
          Key = "123",
          UserProfile =
              Tailcfg.UserProfile(
                  DisplayName = "Person 2",
                  LoginName = "Person 2",
              ),
          NetworkProfile = Tailcfg.NetworkProfile(DomainName = "tailscale.com"),
          LocalUserID = "2",
          ControlURL = null,
      )
  AppTheme {
    UserSwitcherScreen(
        state =
            UserSwitcherUiState(
                showHeaderMenu = false,
                showErrorDialog = null,
                users = listOf(user1, user2),
                currentUser = user1,
                nextUserId = "1",
            ),
        toggleHeaderMenu = {},
        switchProfile = {},
        addProfile = {},
        logout = {},
        login = {},
        showDeleteDialog = {},
        nav =
            UserSwitcherNav(
                backToSettings = {},
                onNavigateHome = {},
                onNavigateCustomControl = {},
                onNavigateToAuthKey = {},
            ),
        isTv = false,
    )
  }
}
