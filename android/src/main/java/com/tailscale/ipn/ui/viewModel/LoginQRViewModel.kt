// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.tailscale.ipn.ui.dataSource.LoginQRDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginQRViewModelFactory(
    private val dataSource: LoginQRDataSource,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(LoginQRViewModel::class.java)) {
      return LoginQRViewModel(dataSource) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}

class LoginQRViewModel(
    private val dataSource: LoginQRDataSource,
) : ViewModel() {
  private val _numCode = MutableStateFlow<String?>(null)
  val numCode: StateFlow<String?> = _numCode.asStateFlow()

  private val _qrCode = MutableStateFlow<ImageBitmap?>(null)
  val qrCode: StateFlow<ImageBitmap?> = _qrCode.asStateFlow()

  // Remove this once changes to admin console allowing input code to be entered are made.

  init {
    viewModelScope.launch {
      dataSource.browseToURL.collect { url ->
        // Extract the string after "https://login.tailscale.com/a/"
        val prefix = "https://login.tailscale.com/a/"
        _qrCode.value = url?.generateQRCode(200)
        _numCode.value = url?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
      }
    }
  }
}

fun String.generateQRCode(size: Int, padding: Int = 0): ImageBitmap? {
  val qrCodeWriter = QRCodeWriter()

  val encodeHints = mapOf<EncodeHintType, Any?>(EncodeHintType.MARGIN to padding)

  val bitmapMatrix =
      try {
        qrCodeWriter.encode(this, BarcodeFormat.QR_CODE, size, size, encodeHints)
      } catch (ex: WriterException) {
        return null
      }

  val qrCode = createBitmap(size, size)

  for (x in 0 until size) {
    for (y in 0 until size) {
      val shouldColorPixel = bitmapMatrix?.get(x, y) ?: false
      val pixelColor = if (shouldColorPixel) Color.BLACK else Color.WHITE
      qrCode[x, y] = pixelColor
    }
  }

  return qrCode.asImageBitmap()
}
