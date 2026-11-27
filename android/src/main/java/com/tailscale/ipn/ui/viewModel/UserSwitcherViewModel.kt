// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.dataSource.LoginActionsDataSource
import com.tailscale.ipn.ui.dataSource.UserSwitcherDataSource
import com.tailscale.ipn.ui.model.ErrorDialogType
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.repository.ProfilesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserSwitcherNav(
    val backToSettings: () -> Unit,
    val onNavigateHome: () -> Unit,
    val onNavigateCustomControl: () -> Unit,
    val onNavigateToAuthKey: () -> Unit,
)

class UserSwitcherViewModelFactory(
    private val dataSource: UserSwitcherDataSource,
    private val loginActions: LoginActionsDataSource,
    private val profilesRepository: ProfilesRepository,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(UserSwitcherViewModel::class.java)) {
      return UserSwitcherViewModel(dataSource, loginActions, profilesRepository) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}

class UserSwitcherViewModel(
    private val dataSource: UserSwitcherDataSource,
    private val loginActions: LoginActionsDataSource,
    private val profilesRepository: ProfilesRepository,
) : ViewModel() {
  val netmap = dataSource.netmap
  val loggedInUser = profilesRepository.currentProfile
  val loginProfiles = profilesRepository.profiles

  // Set to a non-null value to show the appropriate error dialog
  private val _errorDialog = MutableStateFlow<ErrorDialogType?>(null)
  val errorDialog: StateFlow<ErrorDialogType?> = _errorDialog.asStateFlow()

  init {
    profilesRepository.refresh()
  }

  fun login(completionHandler: (Result<Unit>) -> Unit = {}) {
    loginActions.login(scope = viewModelScope, completionHandler = completionHandler)
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
      profilesRepository.refresh()
      completionHandler(result)
    }
  }

  fun showErrorDialog(type: ErrorDialogType) {
    _errorDialog.value = type
  }

  fun clearErrorDialog() {
    _errorDialog.value = null
  }
}
