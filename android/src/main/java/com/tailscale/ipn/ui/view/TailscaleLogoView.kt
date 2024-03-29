// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.timer

// DotsMatrix represents the state of the progress indicator.
typealias DotsMatrix = List<List<Boolean>>

// The initial DotsMatrix that represents the Tailscale logo (T-shaped).
val logoDotsMatrix: DotsMatrix =
    listOf(
        listOf(false, false, false),
        listOf(true, true, true),
        listOf(false, true, false),
    )

@Composable
fun TailscaleLogoView(animated: Boolean = false, modifier: Modifier) {

  val primaryColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
  val secondaryColor: Color = primaryColor.copy(alpha = 0.1f)

  val currentDotsMatrix: StateFlow<DotsMatrix> = MutableStateFlow(logoDotsMatrix)
  var currentDotsMatrixIndex = 0
  fun advanceToNextMatrix() {
    currentDotsMatrixIndex = (currentDotsMatrixIndex + 1) % gameOfLife.size
    val newMatrix =
        if (animated) {
          gameOfLife[currentDotsMatrixIndex]
        } else {
          logoDotsMatrix
        }
    currentDotsMatrix.set(newMatrix)
  }

  if (animated) {
    timer(period = 300L) { advanceToNextMatrix() }
  }

  @Composable
  fun EnabledDot(modifier: Modifier) {
    Canvas(modifier = modifier, onDraw = { drawCircle(primaryColor) })
  }

  @Composable
  fun DisabledDot(modifier: Modifier) {
    Canvas(modifier = modifier, onDraw = { drawCircle(secondaryColor) })
  }

  BoxWithConstraints(modifier) {
    val currentMatrix = currentDotsMatrix.collectAsState().value
    Column(verticalArrangement = Arrangement.spacedBy(this@BoxWithConstraints.maxWidth.div(8))) {
      for (y in 0..2) {
        Row(horizontalArrangement = Arrangement.spacedBy(this@BoxWithConstraints.maxWidth.div(8))) {
          for (x in 0..2) {
            if (currentMatrix[y][x]) {
              EnabledDot(Modifier.size(this@BoxWithConstraints.maxWidth.div(4)))
            } else {
              DisabledDot(Modifier.size(this@BoxWithConstraints.maxWidth.div(4)))
            }
          }
        }
      }
    }
  }
}

val gameOfLife: List<DotsMatrix> =
    listOf(
        listOf(
            listOf(false, true, true),
            listOf(true, false, true),
            listOf(false, false, true),
        ),
        listOf(
            listOf(false, true, true),
            listOf(false, false, true),
            listOf(false, true, false),
        ),
        listOf(
            listOf(false, true, true),
            listOf(false, false, false),
            listOf(false, false, true),
        ),
        listOf(
            listOf(false, false, true),
            listOf(false, true, false),
            listOf(false, false, false),
        ),
        listOf(
            listOf(false, true, false),
            listOf(false, false, false),
            listOf(false, false, false),
        ),
        listOf(
            listOf(false, false, false),
            listOf(false, false, true),
            listOf(false, false, false),
        ),
        listOf(
            listOf(false, false, false),
            listOf(false, false, false),
            listOf(false, false, false),
        ),
        listOf(
            listOf(false, false, true),
            listOf(false, false, false),
            listOf(false, false, false),
        ),
        listOf(
            listOf(false, false, false),
            listOf(false, false, false),
            listOf(true, false, false),
        ),
        listOf(listOf(false, false, false), listOf(false, false, false), listOf(true, true, false)),
        listOf(listOf(false, false, false), listOf(true, false, false), listOf(true, true, false)),
        listOf(listOf(false, false, false), listOf(true, true, false), listOf(false, true, false)),
        listOf(listOf(false, false, false), listOf(true, true, false), listOf(false, true, true)),
        listOf(listOf(false, false, false), listOf(true, true, true), listOf(false, false, true)),
        listOf(listOf(false, true, false), listOf(true, true, true), listOf(true, false, true)))
