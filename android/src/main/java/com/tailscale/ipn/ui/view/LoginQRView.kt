// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.LoginQRViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginQRView(onDismiss: () -> Unit = {}, model: LoginQRViewModel = viewModel()) {
  Surface(color = MaterialTheme.colorScheme.scrim, modifier = Modifier.fillMaxSize()) {
    Dialog(onDismissRequest = onDismiss) {
      val image by model.qrCode.collectAsState()
      val numCode by model.numCode.collectAsState()
      Column(
          modifier =
              Modifier.clip(RoundedCornerShape(10.dp))
                  .background(MaterialTheme.colorScheme.surfaceContainer)
                  .padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
          horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.scan_to_connect_to_your_tailnet),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center)

            Box(
                modifier =
                    Modifier.size(200.dp)
                        .background(MaterialTheme.colorScheme.onSurface),
                contentAlignment = Alignment.Center) {
                  image?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "Scan to login",
                        modifier = Modifier.fillMaxSize())
                  }
                }
            Text(
                text = stringResource(R.string.enter_code_to_connect_to_tailnet),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)

            numCode?.let {
              Box(
                  modifier =
                      Modifier
                          .clip(RoundedCornerShape(6.dp))
                          .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                  contentAlignment = Alignment.Center) {
                    Text(
                        text =it,
                        style =
                            MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface)
                  }
            }
            Button(onClick = onDismiss, modifier = Modifier.padding(top = 16.dp)) {
              Text(text = stringResource(R.string.dismiss))
            }
          }
    }
  }
}

@Composable
@Preview
fun LoginQRViewPreview() {
  val vm = LoginQRViewModel()
  vm.qrCode.set(vm.generateQRCode("https://tailscale.com", 200, 0))
  vm.numCode.set("123456789")
  AppTheme { LoginQRView({}, vm) }
}
