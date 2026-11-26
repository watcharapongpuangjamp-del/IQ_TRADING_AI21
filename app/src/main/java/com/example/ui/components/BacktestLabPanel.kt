package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.engine.LocalStrategySelectorEngine
import com.example.model.BacktestReport
import com.example.model.SUPPORTED_INSTRUMENTS
import com.example.model.Timeframe
import com.example.ui.theme.LocalTradingColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BacktestLabPanel(
    defaultInstrument: String,
    defaultStake: Double,
    report: BacktestReport?,
    isBusy: Boolean,
    onRunBacktest: (instrument: String, timeframe: Timeframe, strategyId: String, stakeAmount: Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val catalog = remember { LocalStrategySelectorEngine.listCatalogIdsAndNames() }

    var selectedInstrument by remember(defaultInstrument) { mutableStateOf(defaultInstrument) }
    var selectedTimeframe by remember { mutableStateOf(Timeframe.M1) }
    var selectedStrategyId by remember { mutableStateOf(catalog.first().first) }
    var stakeAmount by remember(defaultStake) { mutableDoubleStateOf(defaultStake.coerceIn(5.0, 250.0)) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            color = tradingColors.surfaceElevated,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, tradingColors.cyan.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("backtest_config_card")
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Science,
                        contentDescription = "Walk-Forward Backtest Lab",
                        tint = tradingColors.cyan
                    )
                    Column {
                        Text(
                            text = "Walk-Forward Backtesting Lab",
                            style = MaterialTheme.typography.titleMedium,
                            color = tradingColors.cyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "จำลองย้อนหลังแบบ Zero Look-Ahead (คำนวณเฉพาะแท่งปิดถึง t แล้วปิดผลที่ t+1)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "สินทรัพย์ทดสอบ",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SUPPORTED_INSTRUMENTS.take(5).forEach { spec ->
                        FilterChip(
                            selected = selectedInstrument == spec.symbol,
                            onClick = { selectedInstrument = spec.symbol },
                            label = { Text(spec.symbol, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = tradingColors.cyanContainer,
                                selectedLabelColor = tradingColors.cyan
                            ),
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }

                Text(
                    text = "Timeframe",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Timeframe.entries.forEach { tf ->
                        FilterChip(
                            selected = selectedTimeframe == tf,
                            onClick = { selectedTimeframe = tf },
                            label = { Text(tf.code, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = tradingColors.cyanContainer,
                                selectedLabelColor = tradingColors.cyan
                            ),
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }

                Text(
                    text = "เลือกกลยุทธ์ทดสอบ",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    catalog.forEach { (id, name) ->
                        FilterChip(
                            selected = selectedStrategyId == id,
                            onClick = { selectedStrategyId = id },
                            label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = tradingColors.bullishContainer,
                                selectedLabelColor = tradingColors.bullish
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("backtest_strategy_$id")
                        )
                    }
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("เงินเดิมพันจำลองต่อไม้", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "$${stakeAmount.roundToInt()}",
                            style = MaterialTheme.typography.labelLarge,
                            color = tradingColors.cyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = stakeAmount.toFloat(),
                        onValueChange = { stakeAmount = it.roundToInt().toDouble() },
                        valueRange = 5f..250f,
                        colors = SliderDefaults.colors(
                            thumbColor = tradingColors.cyan,
                            activeTrackColor = tradingColors.cyan,
                            inactiveTrackColor = tradingColors.cardBorder
                        )
                    )
                }

                Button(
                    onClick = {
                        onRunBacktest(
                            selectedInstrument,
                            selectedTimeframe,
                            selectedStrategyId,
                            stakeAmount
                        )
                    },
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tradingColors.cyan,
                        contentColor = tradingColors.surfaceCanvas
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("run_backtest_button")
                ) {
                    Text(
                        text = if (isBusy) "กำลังประมวลผล Walk-Forward..." else "รัน Walk-Forward Backtest",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (report != null) {
            BacktestResultsCard(report = report)
        }
    }
}

@Composable
private fun BacktestResultsCard(report: BacktestReport) {
    val tradingColors = LocalTradingColors.current
    val netPositive = report.netPnl >= 0.0
    val netColor = if (netPositive) tradingColors.bullish else tradingColors.bearish

    Surface(
        color = tradingColors.surfaceElevated,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, netColor.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("backtest_results_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${report.strategyName} (${report.instrument} • ${report.timeframe})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${report.sourceLabel} • ตรวจ ${report.barsEvaluated} แท่ง • ${report.totalTrades} ไม้",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${if (netPositive) "+" else "-"}$${String.format("%.2f", abs(report.netPnl))}",
                    style = MaterialTheme.typography.titleLarge,
                    color = netColor,
                    fontWeight = FontWeight.Bold
                )
            }

            // Metrics strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BacktestStatCell("Win Rate", "${String.format("%.1f%%", report.winRatePct)} (${report.wins}/${report.totalTrades})", tradingColors.cyan, Modifier.weight(1f))
                BacktestStatCell("Profit Factor", String.format("%.2f", report.profitFactor), if (report.profitFactor >= 1.2) tradingColors.bullish else tradingColors.warning, Modifier.weight(1f))
                BacktestStatCell("Max DD / EV", "$${String.format("%.0f", report.maxDrawdown)} / ${String.format("%+.2f", report.expectancy)}", tradingColors.warning, Modifier.weight(1f))
            }

            // Equity Curve Canvas
            if (report.equityCurve.size >= 2) {
                Text(
                    text = "Equity Curve จำลอง",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(tradingColors.surfaceCanvas, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val pts = report.equityCurve
                        val minVal = pts.minOrNull() ?: 0.0
                        val maxVal = pts.maxOrNull() ?: 0.0
                        val range = max(10.0, maxVal - minVal)
                        val w = size.width
                        val h = size.height

                        val zeroY = ((maxVal - 0.0) / range).toFloat().coerceIn(0f, 1f) * h
                        drawLine(
                            color = tradingColors.cardBorder,
                            start = Offset(0f, zeroY),
                            end = Offset(w, zeroY),
                            strokeWidth = 1.5f
                        )

                        val path = Path()
                        val stepX = w / max(1, pts.size - 1)
                        pts.forEachIndexed { idx, v ->
                            val x = idx * stepX
                            val y = ((maxVal - v) / range).toFloat().coerceIn(0f, 1f) * h
                            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = netColor,
                            style = Stroke(width = 4f, cap = StrokeCap.Round)
                        )
                    }
                }
            }

            if (report.simulatedTrades.isNotEmpty()) {
                Text(
                    text = "ตัวอย่างออเดอร์จำลองล่าสุด (${report.simulatedTrades.size} ไม้)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    report.simulatedTrades.takeLast(8).reversed().forEach { sample ->
                        val won = sample.status == "WON"
                        val col = if (won) tradingColors.bullish else tradingColors.bearish
                        Surface(
                            color = tradingColors.surfaceCanvas,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, tradingColors.cardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${sample.direction} • ${sample.strategyName}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Regime: ${sample.marketRegime} • ${String.format("%.5f", sample.entryPrice)} -> ${String.format("%.5f", sample.exitPrice)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "${if (sample.pnl >= 0) "+" else "-"}$${String.format("%.2f", abs(sample.pnl))}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = col,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BacktestStatCell(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    Surface(
        color = tradingColors.surfaceCanvas,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, tradingColors.cardBorder),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
