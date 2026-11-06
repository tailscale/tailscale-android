// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.dataSource.LoginQRDataSource
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.viewModel.LoginQRViewModel
import com.tailscale.ipn.ui.viewModel.LoginQRViewModelFactory
import com.tailscale.ipn.ui.viewModel.generateQRCode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginQRView(
    onDismiss: () -> Unit = {},
    dataSource: LoginQRDataSource,
) {
  val factory = remember(dataSource) { LoginQRViewModelFactory(dataSource) }
  val loginQRViewModel: LoginQRViewModel = viewModel(factory = factory)

  val image by loginQRViewModel.qrCode.collectAsStateWithLifecycle()
  val numCode by loginQRViewModel.numCode.collectAsStateWithLifecycle()

  LoginQRScreen(
      state =
          LoginQRUiState(
              image = image,
              numCode = numCode,
          ),
      onDismiss = onDismiss,
  )
}

private data class LoginQRUiState(
    val image: ImageBitmap?,
    val numCode: String?,
)

@Composable
private fun LoginQRScreen(
    state: LoginQRUiState,
    onDismiss: () -> Unit,
) {
  Surface(color = MaterialTheme.colorScheme.scrim, modifier = Modifier.fillMaxSize()) {
    Dialog(onDismissRequest = onDismiss) {
      Column(
          modifier =
              Modifier.clip(RoundedCornerShape(10.dp))
                  .background(MaterialTheme.colorScheme.surfaceContainer)
                  .padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
            text = stringResource(R.string.scan_to_connect_to_your_tailnet),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Box(
            modifier = Modifier.size(200.dp).background(MaterialTheme.colorScheme.onSurface),
            contentAlignment = Alignment.Center,
        ) {
          state.image?.let {
            Image(
                bitmap = it,
                contentDescription = "Scan to login",
                modifier = Modifier.fillMaxSize(),
            )
          }
        }
        Text(
            text = stringResource(R.string.enter_code_to_connect_to_tailnet),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        state.numCode?.let {
          Box(
              modifier =
                  Modifier.clip(RoundedCornerShape(6.dp))
                      .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
              contentAlignment = Alignment.Center,
          ) {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
        Button(onClick = onDismiss, modifier = Modifier.padding(top = 16.dp)) {
          Text(text = stringResource(R.string.dismiss))
        }
      }
    }
  }
}

@Preview(showSystemUi = true)
@Composable
private fun LoginQRViewPreview() {
  AppTheme {
    LoginQRScreen(
        state =
            LoginQRUiState(
                image = "https://tailscale.com".generateQRCode(200),
                numCode = "123456789",
            ),
        onDismiss = {},
    )
  }
}
