// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.dataSource.LoginActionsDataSource
import com.tailscale.ipn.ui.dataSource.UserSwitcherDataSource
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.UserID
import com.tailscale.ipn.ui.view.ErrorDialogType
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UserSwitcherNav(
    val backToSettings: () -> Unit,
    val onNavigateHome: () -> Unit,
    val onNavigateCustomControl: () -> Unit,
    val onNavigateToAuthKey: () -> Unit,
)

class UserSwitcherViewModelFactory(
    private val dataSource: UserSwitcherDataSource,
    private val loginActions: LoginActionsDataSource,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(UserSwitcherViewModel::class.java)) {
      return UserSwitcherViewModel(dataSource, loginActions) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}

class UserSwitcherViewModel(
    private val dataSource: UserSwitcherDataSource,
    private val loginActions: LoginActionsDataSource,
) : ViewModel() {

  private val tag = this::class.simpleName

  val netmap = dataSource.netmap
  private val _loggedInUser = MutableStateFlow<IpnLocal.LoginProfile?>(null)
  val loggedInUser: StateFlow<IpnLocal.LoginProfile?> = _loggedInUser

  private val _loginProfiles = MutableStateFlow<List<IpnLocal.LoginProfile>?>(null)
  val loginProfiles: StateFlow<List<IpnLocal.LoginProfile>?> = _loginProfiles

  // Set to a non-null value to show the appropriate error dialog
  private val _errorDialog = MutableStateFlow<ErrorDialogType?>(null)
  val errorDialog: StateFlow<ErrorDialogType?> = _errorDialog.asStateFlow()

  // True if we should render the kebab menu
  private val _showHeaderMenu = MutableStateFlow(false)
  val showHeaderMenu: StateFlow<Boolean> = _showHeaderMenu.asStateFlow()

  private var selfNodeUserId: UserID? = null

  init {
    viewModelScope.launch { dataSource.state.collect { loadProfiles() } }

    viewModelScope.launch {
      dataSource.netmap.collect { netmap ->
        val userId = netmap?.SelfNode?.User
        if (userId != selfNodeUserId) {
          selfNodeUserId = userId
          loadProfiles()
        }
      }
    }
  }

  private fun loadProfiles() {
    dataSource.loadProfiles(
        scope = viewModelScope,
        onProfiles = { result ->
          result
              .onSuccess { _loginProfiles.value = it }
              .onFailure { TSLog.e(tag, "Error loading profiles: ${it.message}") }
        },
        onCurrentProfile = { result ->
          result
              .onSuccess { _loggedInUser.value = if (it.isEmpty()) null else it }
              .onFailure { TSLog.e(tag, "Error loading current profile: ${it.message}") }
        },
    )
  }

  fun login(completionHandler: (Result<Unit>) -> Unit = {}) {
    loginActions.login(viewModelScope, completionHandler)
  }

  fun logout(completionHandler: (Result<String>) -> Unit = {}) {
    dataSource.logout(viewModelScope, completionHandler)
  }

  fun switchProfile(
      profile: IpnLocal.LoginProfile,
      completionHandler: (Result<String>) -> Unit,
  ) {
    dataSource.switchProfile(viewModelScope, profile, completionHandler)
  }

  fun addProfile(completionHandler: (Result<String>) -> Unit) {
    dataSource.addProfile(viewModelScope) { result ->
      if (result.isSuccess) {
        loginActions.login(viewModelScope)
      }
      dataSource.startVPN()
      completionHandler(result)
    }
  }

  fun deleteProfile(
      profile: IpnLocal.LoginProfile,
      completionHandler: (Result<String>) -> Unit,
  ) {
    dataSource.deleteProfile(viewModelScope, profile) { result ->
      loadProfiles()
      completionHandler(result)
    }
  }

  fun showErrorDialog(type: ErrorDialogType) {
    _errorDialog.value = type
  }

  fun clearErrorDialog() {
    _errorDialog.value = null
  }

  fun setHeaderMenuExpanded(expanded: Boolean) {
    _showHeaderMenu.value = expanded
  }
}
