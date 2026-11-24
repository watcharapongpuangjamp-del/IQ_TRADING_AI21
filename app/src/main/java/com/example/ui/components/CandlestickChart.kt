package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.Candle
import com.example.model.TechnicalSnapshot
import com.example.ui.theme.LocalTradingColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class SubPanelIndicator(val label: String) {
    RSI("RSI (14)"),
    MACD("MACD Histogram")
}

@Composable
fun CandlestickChartPanel(
    candles: List<Candle>,
    snapshot: TechnicalSnapshot?,
    decimals: Int,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    var showBollinger by remember { mutableStateOf(true) }
    var showEma by remember { mutableStateOf(true) }
    var subPanel by remember { mutableStateOf(SubPanelIndicator.RSI) }
    var visibleCount by remember { mutableFloatStateOf(42f) }
    var panOffsetCandles by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tradingColors.surfaceElevated)
            .border(1.dp, tradingColors.cardBorder, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = showEma,
                    onClick = { showEma = !showEma },
                    label = { Text("EMA", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tradingColors.cyanContainer,
                        selectedLabelColor = tradingColors.cyan
                    ),
                    modifier = Modifier.testTag("toggle_ema_chip")
                )
                FilterChip(
                    selected = showBollinger,
                    onClick = { showBollinger = !showBollinger },
                    label = { Text("BB", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tradingColors.warningContainer,
                        selectedLabelColor = tradingColors.warning
                    ),
                    modifier = Modifier.testTag("toggle_bb_chip")
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SubPanelIndicator.entries.forEach { ind ->
                    FilterChip(
                        selected = subPanel == ind,
                        onClick = { subPanel = ind },
                        label = { Text(ind.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tradingColors.bullishContainer,
                            selectedLabelColor = tradingColors.bullish
                        ),
                        modifier = Modifier.testTag("subpanel_${ind.name.lowercase()}_chip")
                    )
                }
            }
        }

        if (candles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "กำลังโหลดข้อมูลแท่งเทียนเรียลไทม์...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val clampedVisible = visibleCount.roundToInt().coerceIn(18, min(90, candles.size))
            val maxOffset = (candles.size - clampedVisible).coerceAtLeast(0)
            val offsetInt = panOffsetCandles.roundToInt().coerceIn(0, maxOffset)
            val endIndex = candles.size - offsetInt
            val startIndex = (endIndex - clampedVisible).coerceAtLeast(0)
            val windowCandles = candles.subList(startIndex, endIndex)
            val lastClose = candles.last().close

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tradingColors.surfaceCanvas)
                    .pointerInput(candles.size) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (zoom != 1f) {
                                visibleCount = (visibleCount / zoom).coerceIn(18f, 90f)
                            }
                            if (abs(pan.x) > 0.5f) {
                                panOffsetCandles = (panOffsetCandles + pan.x / 18f)
                                    .coerceIn(0f, maxOffset.toFloat())
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp, horizontal = 6.dp)) {
                    val w = size.width
                    val h = size.height
                    if (windowCandles.isEmpty() || w <= 0f || h <= 0f) return@Canvas

                    var minPrice = windowCandles.minOf { it.low }
                    var maxPrice = windowCandles.maxOf { it.high }

                    if (showBollinger && snapshot != null && snapshot.bbUpperSeries.size == candles.size) {
                        val bbUp = snapshot.bbUpperSeries.subList(startIndex, endIndex)
                        val bbLow = snapshot.bbLowerSeries.subList(startIndex, endIndex)
                        minPrice = min(minPrice, bbLow.minOrNull() ?: minPrice)
                        maxPrice = max(maxPrice, bbUp.maxOrNull() ?: maxPrice)
                    }

                    val priceSpan = (maxPrice - minPrice).coerceAtLeast(1e-9)
                    val paddedMin = minPrice - priceSpan * 0.08
                    val paddedMax = maxPrice + priceSpan * 0.08
                    val totalSpan = (paddedMax - paddedMin).coerceAtLeast(1e-9)

                    fun priceToY(price: Double): Float {
                        val ratio = ((price - paddedMin) / totalSpan).toFloat()
                        return h - (ratio * h)
                    }

                    // Subtle coordinate grid
                    val gridColor = Color(0xFF1A2336)
                    for (g in 1..3) {
                        val gy = h * (g / 4f)
                        drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
                    }

                    val stepX = w / windowCandles.size.toFloat()
                    val bodyWidth = (stepX * 0.58f).coerceIn(3f, 16f)

                    // Bollinger Bands Envelope
                    if (showBollinger && snapshot != null && snapshot.bbUpperSeries.size == candles.size) {
                        val upSlice = snapshot.bbUpperSeries.subList(startIndex, endIndex)
                        val lowSlice = snapshot.bbLowerSeries.subList(startIndex, endIndex)
                        val upPath = Path()
                        val lowPath = Path()
                        upSlice.forEachIndexed { idx, v ->
                            val x = idx * stepX + stepX / 2f
                            val y = priceToY(v)
                            if (idx == 0) upPath.moveTo(x, y) else upPath.lineTo(x, y)
                        }
                        lowSlice.forEachIndexed { idx, v ->
                            val x = idx * stepX + stepX / 2f
                            val y = priceToY(v)
                            if (idx == 0) lowPath.moveTo(x, y) else lowPath.lineTo(x, y)
                        }
                        drawPath(
                            upPath,
                            color = tradingColors.warning.copy(alpha = 0.55f),
                            style = Stroke(width = 1.6f)
                        )
                        drawPath(
                            lowPath,
                            color = tradingColors.warning.copy(alpha = 0.55f),
                            style = Stroke(width = 1.6f)
                        )
                    }

                    // Candles
                    windowCandles.forEachIndexed { idx, c ->
                        val centerX = idx * stepX + stepX / 2f
                        val isBull = c.close >= c.open
                        val color = if (isBull) tradingColors.bullish else tradingColors.bearish
                        val yHigh = priceToY(c.high)
                        val yLow = priceToY(c.low)
                        val yOpen = priceToY(c.open)
                        val yClose = priceToY(c.close)

                        drawLine(
                            color = color,
                            start = Offset(centerX, yHigh),
                            end = Offset(centerX, yLow),
                            strokeWidth = 2f
                        )

                        val top = min(yOpen, yClose)
                        val bodyH = max(abs(yClose - yOpen), 2.5f)
                        drawRect(
                            color = color,
                            topLeft = Offset(centerX - bodyWidth / 2f, top),
                            size = Size(bodyWidth, bodyH)
                        )
                    }

                    // EMA Fast & Slow curves
                    if (showEma && snapshot != null && snapshot.emaFastSeries.size == candles.size) {
                        val fastSlice = snapshot.emaFastSeries.subList(startIndex, endIndex)
                        val slowSlice = snapshot.emaSlowSeries.subList(startIndex, endIndex)
                        val fastPath = Path()
                        val slowPath = Path()
                        fastSlice.forEachIndexed { idx, v ->
                            val x = idx * stepX + stepX / 2f
                            val y = priceToY(v)
                            if (idx == 0) fastPath.moveTo(x, y) else fastPath.lineTo(x, y)
                        }
                        slowSlice.forEachIndexed { idx, v ->
                            val x = idx * stepX + stepX / 2f
                            val y = priceToY(v)
                            if (idx == 0) slowPath.moveTo(x, y) else slowPath.lineTo(x, y)
                        }
                        drawPath(fastPath, color = tradingColors.cyan, style = Stroke(width = 2.4f))
                        drawPath(slowPath, color = Color(0xFFA78BFA), style = Stroke(width = 2.4f))
                    }

                    // Current Price Dashed Line
                    val lastY = priceToY(lastClose)
                    drawLine(
                        color = tradingColors.cyan.copy(alpha = 0.85f),
                        start = Offset(0f, lastY),
                        end = Offset(w, lastY),
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                    )
                }

                // Floating live price badge
                val formattedPrice = String.format("%.${decimals}f", lastClose)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tradingColors.cyanContainer.copy(alpha = 0.92f))
                        .border(1.dp, tradingColors.cyan.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = formattedPrice,
                        style = MaterialTheme.typography.labelMedium,
                        color = tradingColors.cyan
                    )
                }
            }

            // Sub-panel Chart (RSI or MACD Histogram)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tradingColors.surfaceCanvas)
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp)) {
                    val w = size.width
                    val h = size.height
                    if (snapshot == null || w <= 0f || h <= 0f) return@Canvas

                    when (subPanel) {
                        SubPanelIndicator.RSI -> {
                            if (snapshot.rsiSeries.size != candles.size) return@Canvas
                            val slice = snapshot.rsiSeries.subList(startIndex, endIndex)
                            val y70 = h * (1f - 0.70f)
                            val y30 = h * (1f - 0.30f)
                            drawLine(
                                color = tradingColors.bearish.copy(alpha = 0.35f),
                                start = Offset(0f, y70),
                                end = Offset(w, y70),
                                strokeWidth = 1f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                            )
                            drawLine(
                                color = tradingColors.bullish.copy(alpha = 0.35f),
                                start = Offset(0f, y30),
                                end = Offset(w, y30),
                                strokeWidth = 1f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                            )
                            val stepX = w / slice.size.toFloat()
                            val path = Path()
                            slice.forEachIndexed { idx, rsi ->
                                val x = idx * stepX + stepX / 2f
                                val y = h * (1f - (rsi.toFloat() / 100f)).coerceIn(0f, 1f)
                                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, color = tradingColors.cyan, style = Stroke(width = 2.2f))
                        }

                        SubPanelIndicator.MACD -> {
                            if (snapshot.macdHistSeries.size != candles.size) return@Canvas
                            val slice = snapshot.macdHistSeries.subList(startIndex, endIndex)
                            val maxAbs = slice.maxOfOrNull { abs(it) }?.coerceAtLeast(1e-9) ?: 1.0
                            val zeroY = h / 2f
                            drawLine(
                                color = Color(0xFF243047),
                                start = Offset(0f, zeroY),
                                end = Offset(w, zeroY),
                                strokeWidth = 1f
                            )
                            val stepX = w / slice.size.toFloat()
                            val barW = (stepX * 0.55f).coerceIn(2f, 12f)
                            slice.forEachIndexed { idx, hist ->
                                val cx = idx * stepX + stepX / 2f
                                val barH = ((abs(hist) / maxAbs).toFloat() * (h * 0.45f)).coerceAtLeast(1.5f)
                                val top = if (hist >= 0) zeroY - barH else zeroY
                                val col = if (hist >= 0) tradingColors.bullish else tradingColors.bearish
                                drawRect(
                                    color = col.copy(alpha = 0.85f),
                                    topLeft = Offset(cx - barW / 2f, top),
                                    size = Size(barW, barH)
                                )
                            }
                        }
                    }
                }

                val indicatorText = when (subPanel) {
                    SubPanelIndicator.RSI -> "RSI: ${String.format("%.1f", snapshot?.rsi ?: 50.0)}"
                    SubPanelIndicator.MACD -> "MACD Hist: ${String.format("%.5f", snapshot?.macdHistogram ?: 0.0)}"
                }
                Text(
                    text = indicatorText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
