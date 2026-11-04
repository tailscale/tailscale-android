// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.dataSource.AuthKeyLoginDataSource
import com.tailscale.ipn.ui.dataSource.CustomControlURLLoginDataSource
import com.tailscale.ipn.ui.view.ErrorDialogType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CustomLoginNav(
    val backToSettings: () -> Unit,
    val onNavigateHome: () -> Unit,
)

class LoginWithAuthKeyViewModelFactory(
    private val loginActions: AuthKeyLoginDataSource,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(LoginWithAuthKeyViewModel::class.java)) {
      return LoginWithAuthKeyViewModel(loginActions) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}

class LoginWithAuthKeyViewModel(
    private val loginActions: AuthKeyLoginDataSource,
) : ViewModel() {
  private val _errorDialog = MutableStateFlow<ErrorDialogType?>(null)
  val errorDialog: StateFlow<ErrorDialogType?> = _errorDialog.asStateFlow()

  // Sets the auth key and invokes the login flow
  fun setAuthKey(authKey: String, onSuccess: () -> Unit) {
    // The most basic of checks for auth key syntax
    if (authKey.isEmpty()) {
      _errorDialog.value = ErrorDialogType.INVALID_AUTH_KEY
      return
    }
    loginActions.loginWithAuthKey(viewModelScope, authKey) {
      it.onFailure { _errorDialog.value = ErrorDialogType.ADD_PROFILE_FAILED }
      it.onSuccess { onSuccess() }
    }
  }

  fun clearErrorDialog() {
    _errorDialog.value = null
  }
}

class LoginWithCustomControlURLViewModelFactory(
    private val loginActions: CustomControlURLLoginDataSource,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(LoginWithCustomControlURLViewModel::class.java)) {
      return LoginWithCustomControlURLViewModel(loginActions) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}

class LoginWithCustomControlURLViewModel(
    private val loginActions: CustomControlURLLoginDataSource,
) : ViewModel() {
  private val _errorDialog = MutableStateFlow<ErrorDialogType?>(null)
  val errorDialog: StateFlow<ErrorDialogType?> = _errorDialog.asStateFlow()

  // Sets the custom control URL and invokes the login flow
  fun setControlURL(urlStr: String, onSuccess: () -> Unit) {
    // Some basic checks that the entered URL is "reasonable".  The underlying
    // localAPIClient will use the default server if we give it a broken URL,
    // but we can make sure we can construct a URL from the input string and
    // ensure it has an http/https scheme
    when (
        urlStr.startsWith("http", ignoreCase = true) && urlStr.contains("://") && urlStr.length > 7
    ) {
      false -> {
        _errorDialog.value = ErrorDialogType.INVALID_CUSTOM_URL
        return
      }
      true -> {
        loginActions.loginWithCustomControlURL(viewModelScope, urlStr) {
          it.onFailure { _errorDialog.value = ErrorDialogType.ADD_PROFILE_FAILED }
          it.onSuccess { onSuccess() }
        }
      }
    }
  }

  fun clearErrorDialog() {
    _errorDialog.value = null
  }
}
