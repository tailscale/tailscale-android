// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

// AutoResizingText automatically resizes text up to the specified minFontSize in order to avoid
// overflowing. It is based on https://stackoverflow.com/a/66090448 licensed under CC BY-SA 4.0.
@Composable
fun AutoResizingText(
    text: String,
    minFontSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current
) {
  var textStyle = remember { mutableStateOf(style) }
  var textOverflow = remember { mutableStateOf(TextOverflow.Clip) }
  var readyToDraw = remember { mutableStateOf(false) }

  Text(
      text = text,
      modifier = modifier.drawWithContent { if (readyToDraw.value) drawContent() },
      color = color,
      fontSize = fontSize,
      fontStyle = fontStyle,
      fontWeight = fontWeight,
      fontFamily = fontFamily,
      letterSpacing = letterSpacing,
      textDecoration = textDecoration,
      textAlign = textAlign,
      lineHeight = lineHeight,
      overflow = textOverflow.value,
      maxLines = maxLines,
      softWrap = false,
      style = textStyle.value,
      onTextLayout = { result ->
        if (result.didOverflowWidth) {
          var newSize = textStyle.value.fontSize * 0.9
          if (newSize < minFontSize) {
            newSize = minFontSize
            textOverflow.value = overflow
          }
          textStyle.value = textStyle.value.copy(fontSize = newSize)
        } else {
          readyToDraw.value = true
        }
        onTextLayout?.let { it(result) }
      })
}
