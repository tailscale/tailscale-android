// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

// TODO(angott): must mention usage of com.patrykandpatrick.vico library in LICENSES
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.CartesianLayerPadding
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.util.ConnectionMode
import com.tailscale.ipn.ui.viewModel.PingViewModel
import java.text.DecimalFormat
import kotlinx.coroutines.runBlocking

@Composable
fun PingView(model: PingViewModel = viewModel()) {
  val connectionMode: ConnectionMode by
      model.connectionMode.collectAsState(initial = ConnectionMode.NotConnected())
  val peer: Tailcfg.Node? by model.peer.collectAsState()
  val lastLatencyValue: String by model.lastLatencyValue.collectAsState()
  val pingValues: List<Double> by model.latencyValues.collectAsState()
  val errorMessage: String? by model.errorMessage.collectAsState()

  val modelProducer = remember { CartesianChartModelProducer() }

  LaunchedEffect(pingValues) {
    modelProducer.runTransaction {
      if (pingValues.isNotEmpty()) {
        lineModel {
          series(
              x = pingValues.indices.map { it + 1 },
              y = pingValues,
          )
        }
      }
    }
  }

  Column(
      verticalArrangement = Arrangement.spacedBy(16.dp),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 36.dp),
  ) {
    Row {
      Column {
        Text(
            stringResource(R.string.pinging_node_name, peer?.ComputedName ?: "???"),
            fontStyle = MaterialTheme.typography.titleLarge.fontStyle,
            fontWeight = FontWeight.Bold,
        )
        if (pingValues.isNotEmpty()) {
          AnimatedContent(targetState = connectionMode, contentKey = { it.contentKey() }) {
              targetConnectionMode ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              Icon(
                  painter = painterResource(id = targetConnectionMode.iconDrawable()),
                  contentDescription = null,
                  tint = targetConnectionMode.color(),
              )
              Text(
                  targetConnectionMode.titleString(),
                  fontSize = MaterialTheme.typography.bodySmall.fontSize,
                  color = targetConnectionMode.color(),
              )
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
          },
      ) { latency ->
        Text(
            latency,
            fontFamily = FontFamily.Monospace,
            fontSize = MaterialTheme.typography.titleLarge.fontSize,
            textAlign = TextAlign.Right,
            modifier = Modifier.fillMaxWidth(),
        )
      }
    }

    if (pingValues.isNotEmpty()) {
      PingChart(
          pingValues = pingValues,
          modelProducer = modelProducer,
      )
    } else {
      errorMessage?.also { error ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement =
                Arrangement.spacedBy(6.dp, alignment = Alignment.CenterVertically),
            modifier = Modifier.fillMaxWidth().height(200.dp),
        ) {
          Icon(
              painter = painterResource(id = R.drawable.warning),
              modifier = Modifier.size(48.dp),
              contentDescription = null,
              tint = Color.Red,
          )
          Text(
              stringResource(id = R.string.pingFailed),
              fontSize = MaterialTheme.typography.titleMedium.fontSize,
              fontFamily = MaterialTheme.typography.titleMedium.fontFamily,
              fontWeight = FontWeight.Bold,
              textAlign = TextAlign.Center,
              color = Color.Red,
          )
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
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              TailscaleLogoView(
                  true,
                  usesOnBackgroundColors = false,
                  Modifier.size(36.dp).alpha(0.4f),
              )
            }
          }
    }
  }
}

@Composable
private fun PingChart(
    pingValues: List<Double>,
    modelProducer: CartesianChartModelProducer,
    modifier: Modifier = Modifier,
) {
  val circlePoint =
      rememberShapeComponent(
          shape = CircleShape,
          fill = Fill(MaterialTheme.colorScheme.background),
          strokeFill = Fill(MaterialTheme.colorScheme.surfaceTint),
          strokeThickness = 2.dp,
      )

  // Pads lowest/highest values by 5 instead of lots of empty space 0-lowest value
  val rangeProvider =
      CartesianLayerRangeProvider.fixed(
          minY = (pingValues.minOrNull() ?: 0.0).let { (it - 5.0).coerceAtLeast(0.0) },
          maxY = (pingValues.maxOrNull() ?: 0.0) + 5.0,
      )

  val lineProvider =
      LineCartesianLayer.LineProvider.series(
          LineCartesianLayer.rememberLine(
              fill = LineCartesianLayer.LineFill.single(Fill(MaterialTheme.colorScheme.primary)),
              areaFill =
                  LineCartesianLayer.AreaFill.single(
                      Fill(
                          Brush.verticalGradient(
                              colors =
                                  listOf(
                                      MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                      MaterialTheme.colorScheme.primary.copy(alpha = 0f),
                                  )
                          )
                      )
                  ),
              pointProvider =
                  LineCartesianLayer.PointProvider.single(
                      LineCartesianLayer.Point(
                          component = circlePoint,
                          size = 10.dp,
                      )
                  ),
              interpolator = remember { LineCartesianLayer.Interpolator.cubic() },
          )
      )

  val verticalAxis =
      VerticalAxis.rememberStart(
          valueFormatter =
              remember {
                CartesianValueFormatter { _, value, _ ->
                  DecimalFormat("#;−#").format(value) + " ms"
                }
              },
          itemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 5 }) },
          label =
              rememberAxisLabelComponent(
                  style =
                      TextStyle(
                          color = MaterialTheme.colorScheme.secondary,
                          fontFamily = FontFamily.Monospace,
                          fontSize = 12.sp,
                      ),
                  padding = Insets(end = 8.dp),
              ),
          guideline =
              rememberAxisGuidelineComponent(
                  fill = Fill(MaterialTheme.colorScheme.secondaryContainer),
              ),
      )

  val horizontalAxis =
      HorizontalAxis.rememberBottom(
          itemPlacer = remember { HorizontalAxis.ItemPlacer.segmented(shiftExtremeLines = false) }
      )

  // Each interval is 40.dp spacing + 10.dp point diameter.
  // Reserve half at each edge and cancel Vico's built-in 5.dp point-radius padding.
  val layerPadding =
      CartesianLayerPadding(
          scalableStart = 25.dp,
          scalableEnd = 25.dp,
          unscalableStart = (-5).dp,
          unscalableEnd = (-5).dp,
      )

  CartesianChartHost(
      modifier = modifier,
      chart =
          rememberCartesianChart(
              rememberLineCartesianLayer(
                  rangeProvider = rangeProvider,
                  lineProvider = lineProvider,
                  pointSpacing = 40.dp,
              ),
              startAxis = verticalAxis,
              bottomAxis = horizontalAxis,
              layerPadding = { layerPadding },
          ),
      modelProducer = modelProducer,
      scrollState = rememberVicoScrollState(scrollEnabled = false),
      animationSpec = null,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showSystemUi = true)
@Composable
private fun PingChartPreview() {
  val pingValues = listOf(45.0, 62.0, 48.0, 80.0, 55.0, 42.0, 60.0, 51.0, 47.0, 53.0)
  val modelProducer = remember {
    CartesianChartModelProducer().also { producer ->
      // Previews need chart data populated synchronously.
      runBlocking {
        producer.runTransaction {
          lineModel {
            series(
                x = pingValues.indices.map { it + 1 },
                y = pingValues,
            )
          }
        }
      }
    }
  }

  AppTheme {
    Box(Modifier.fillMaxSize()) {
      ModalBottomSheet(
          onDismissRequest = {},
          sheetState =
              rememberModalBottomSheetState(
                  skipPartiallyExpanded = true,
              ),
      ) {
        PingChart(
            pingValues = pingValues,
            modelProducer = modelProducer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 36.dp),
        )
      }
    }
  }
}
