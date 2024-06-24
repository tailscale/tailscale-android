// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

// TODO(angott): must mention usage of com.patrykandpatrick.vico library in LICENSES
import android.graphics.Typeface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.patrykandpatrick.vico.compose.axis.axisGuidelineComponent
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.component.shapeComponent
import com.patrykandpatrick.vico.compose.component.textComponent
import com.patrykandpatrick.vico.compose.dimensions.dimensionsOf
import com.patrykandpatrick.vico.compose.m3.style.m3ChartStyle
import com.patrykandpatrick.vico.compose.style.ProvideChartStyle
import com.patrykandpatrick.vico.compose.style.currentChartStyle
import com.patrykandpatrick.vico.core.axis.AxisItemPlacer
import com.patrykandpatrick.vico.core.chart.copy
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.util.ConnectionMode
import com.tailscale.ipn.ui.viewModel.PingViewModel
import java.text.DecimalFormat

@Composable
fun PingView(model: PingViewModel = viewModel()) {
  val connectionMode: ConnectionMode by
      model.connectionMode.collectAsState(initial = ConnectionMode.NotConnected())
  val peer: Tailcfg.Node? by model.peer.collectAsState()
  val lastLatencyValue: String by model.lastLatencyValue.collectAsState()
  val pingValues: List<Double> by model.latencyValues.collectAsState()
  val chartEntryModel =
      entryModelOf(
          pingValues.withIndex().map { FloatEntry((it.index + 1).toFloat(), it.value.toFloat()) })
  val errorMessage: String? by model.errorMessage.collectAsState()

  Column(
      verticalArrangement = Arrangement.spacedBy(16.dp),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 36.dp)) {
        Row {
          Column {
            Text(
                stringResource(R.string.pinging_node_name, peer?.ComputedName ?: "???"),
                fontStyle = MaterialTheme.typography.titleLarge.fontStyle,
                fontWeight = FontWeight.Bold)
            if (pingValues.isNotEmpty()) {
              AnimatedContent(targetState = connectionMode, contentKey = { it.contentKey() }) {
                  targetConnectionMode ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  Icon(
                      painter = painterResource(id = targetConnectionMode.iconDrawable()),
                      contentDescription = null,
                      tint = targetConnectionMode.color())
                  Text(
                      targetConnectionMode.titleString(),
                      fontSize = MaterialTheme.typography.bodySmall.fontSize,
                      color = targetConnectionMode.color())
                }
              }
            }
          }

          AnimatedContent(
              targetState = lastLatencyValue,
              transitionSpec = {
                // The new value slides down and fades in, while the previous value slides down
                // and fades out.
                (slideInVertically { height -> -height } + fadeIn())
                    .togetherWith(slideOutVertically { height -> height } + fadeOut())
                    .using(SizeTransform(clip = false))
              }) { latency ->
                Text(
                    latency,
                    fontFamily = FontFamily.Monospace,
                    fontSize = MaterialTheme.typography.titleLarge.fontSize,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth())
              }
        }

        if (pingValues.isNotEmpty()) {
          ProvideChartStyle(chartStyle = m3ChartStyle()) {
            val defaultLines = currentChartStyle.lineChart.lines
            val circlePoint =
                shapeComponent(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.background,
                    strokeColor = MaterialTheme.colorScheme.surfaceTint,
                    strokeWidth = 2.dp)
            Chart(
                chart =
                    lineChart(
                        remember(defaultLines) {
                          defaultLines.map { defaultLine ->
                            defaultLine.copy(point = circlePoint, pointSizeDp = 10.0F)
                          }
                        },
                        spacing = 0.dp,
                    ),
                model = chartEntryModel,
                startAxis =
                    rememberStartAxis(
                        valueFormatter = { value, _ ->
                          DecimalFormat("#;−#").format(value) + " ms"
                        },
                        itemPlacer = remember { AxisItemPlacer.Vertical.default(maxItemCount = 5) },
                        label =
                            textComponent(
                                color = MaterialTheme.colorScheme.secondary,
                                typeface = Typeface.MONOSPACE,
                                padding = dimensionsOf(end = 8.dp)),
                        guideline =
                            axisGuidelineComponent(
                                color = MaterialTheme.colorScheme.secondaryContainer)),
                bottomAxis =
                    rememberBottomAxis(
                        itemPlacer = remember { AxisItemPlacer.Horizontal.default(spacing = 1) },
                        label =
                            textComponent(
                                color = MaterialTheme.colorScheme.secondary,
                                typeface = Typeface.MONOSPACE,
                            ),
                        guideline =
                            axisGuidelineComponent(
                                color = MaterialTheme.colorScheme.secondaryContainer)),
            )
          }
        } else {
          errorMessage?.also { error ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement =
                    Arrangement.spacedBy(6.dp, alignment = Alignment.CenterVertically),
                modifier = Modifier.fillMaxWidth().height(200.dp)) {
                  Icon(
                      painter = painterResource(id = R.drawable.warning),
                      modifier = Modifier.size(48.dp),
                      contentDescription = null,
                      tint = Color.Red)
                  Text(
                      stringResource(id = R.string.pingFailed),
                      fontSize = MaterialTheme.typography.titleMedium.fontSize,
                      fontFamily = MaterialTheme.typography.titleMedium.fontFamily,
                      fontWeight = FontWeight.Bold,
                      textAlign = TextAlign.Center,
                      color = Color.Red)
                  Text(
                      error,
                      textAlign = TextAlign.Center,
                      color = Color.Red,
                  )
                }
          }
              ?: run {
                Column(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                      TailscaleLogoView(
                          true, usesOnBackgroundColors = false, Modifier.size(36.dp).alpha(0.4f))
                    }
              }
        }
      }
}

fun Double.roundedString(decimals: Int): String = "%.${decimals}f".format(this)
