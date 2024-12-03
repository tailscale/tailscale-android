// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.LoginWithAuthKeyViewModel
import com.tailscale.ipn.ui.viewModel.LoginWithCustomControlURLViewModel

data class LoginViewStrings(
    var title: String,
    var explanation: String,
    var inputTitle: String,
    var placeholder: String,
)

@Composable
fun LoginWithCustomControlURLView(
    onNavigateHome: BackNavigation,
    backToSettings: BackNavigation,
    viewModel: LoginWithCustomControlURLViewModel = LoginWithCustomControlURLViewModel()
) {

  Scaffold(
      topBar = {
        Header(
            R.string.add_account,
            onBack = backToSettings,
        )
      }) { innerPadding ->
        val error by viewModel.errorDialog.collectAsState()
        val strings =
            LoginViewStrings(
                title = stringResource(id = R.string.custom_control_menu),
                explanation = stringResource(id = R.string.custom_control_menu_desc),
                inputTitle = stringResource(id = R.string.custom_control_url_title),
                placeholder = stringResource(id = R.string.custom_control_placeholder),
            )

        error?.let { ErrorDialog(type = it, action = { viewModel.errorDialog.set(null) }) }

        LoginView(
            innerPadding = innerPadding,
            strings = strings,
            onSubmitAction = { viewModel.setControlURL(it, onNavigateHome) })
      }
}

@Composable
fun LoginWithAuthKeyView(
    onNavigateHome: BackNavigation,
    backToSettings: BackNavigation,
    viewModel: LoginWithAuthKeyViewModel = LoginWithAuthKeyViewModel()
) {

  Scaffold(
      topBar = {
        Header(
            R.string.add_account,
            onBack = backToSettings,
        )
      }) { innerPadding ->
        val error by viewModel.errorDialog.collectAsState()
        val strings =
            LoginViewStrings(
                title = stringResource(id = R.string.auth_key_title),
                explanation = stringResource(id = R.string.auth_key_explanation),
                inputTitle = stringResource(id = R.string.auth_key_input_title),
                placeholder = stringResource(id = R.string.auth_key_placeholder),
            )
        // Show the error overlay if need be
        error?.let { ErrorDialog(type = it, action = { viewModel.errorDialog.set(null) }) }

        LoginView(
            innerPadding = innerPadding,
            strings = strings,
            onSubmitAction = { viewModel.setAuthKey(it, onNavigateHome) })
      }
}

@Composable
fun LoginView(
    innerPadding: PaddingValues = PaddingValues(16.dp),
    strings: LoginViewStrings,
    onSubmitAction: (String) -> Unit,
) {

  var textVal by remember { mutableStateOf("") }

  Column(
      modifier =
          Modifier.padding(innerPadding)
              .fillMaxWidth()
              .background(MaterialTheme.colorScheme.surface)) {
        ListItem(
            colors = MaterialTheme.colorScheme.listItem,
            headlineContent = { Text(text = strings.title) },
            supportingContent = { Text(text = strings.explanation) })

        ListItem(
            colors = MaterialTheme.colorScheme.listItem,
            headlineContent = { Text(text = strings.inputTitle) },
            supportingContent = {
              OutlinedTextField(
                  modifier = Modifier.fillMaxWidth(),
                  colors =
                      TextFieldDefaults.colors(
                          focusedContainerColor = Color.Transparent,
                          unfocusedContainerColor = Color.Transparent),
                  textStyle = MaterialTheme.typography.bodyMedium,
                  value = textVal,
                  onValueChange = { textVal = it },
                  placeholder = {
                    Text(strings.placeholder, style = MaterialTheme.typography.bodySmall)
                  },
                  keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
              )
            })

        ListItem( 
            colors = MaterialTheme.colorScheme.listItem,
            headlineContent = {
              Box(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onSubmitAction(textVal) },
                    content = { Text(stringResource(id = R.string.add_account_short)) })
              }
            })
      }
}
