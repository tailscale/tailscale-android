// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.BarcodeScanningAnalyzer
import java.util.concurrent.Executors

@Composable
fun QRCodeScannerScreen(
    onBack: () -> Unit,
    loginAtUrl: (String) -> Unit,
) {
    var isScanning by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            Header(
                titleRes = R.string.qr_code_scanner_title,
                onBack = onBack,
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            CameraView(
                analyzer = BarcodeScanningAnalyzer { barcode ->
                    if (isScanning) {
                        isScanning = false
                        onBack()
                        loginAtUrl(barcode)
                    }
                }
            )
        }
    }
}

@Composable
fun CameraView(
    analyzer: BarcodeScanningAnalyzer,
) {
    val localContext = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(localContext) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            // Create a preview view for displaying the camera feed
            val previewView = PreviewView(context)

            // Create a camera preview use case
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            // Set up camera selector (for back camera in this case)
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            // Set up image analysis use case for barcode scanning
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().apply {
                    setAnalyzer(Executors.newSingleThreadExecutor(), analyzer)
                }

            // Bind camera use cases to lifecycle
            runCatching {
                cameraProviderFuture.get().bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis,
                )
            }.onFailure {
                Log.e("CAMERA", "Camera bind error: ${it.localizedMessage}", it)
            }

            previewView
        }
    )
}