package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.DailyPnLSummary
import com.example.model.TradeRecord
import com.example.model.TradeStatus
import com.example.ui.theme.LocalTradingColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun PnLDashboardScreen(
    dailySummaries: List<DailyPnLSummary>,
    trades: List<TradeRecord>,
    todayDateKey: String,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    var selectedTrade by remember { mutableStateOf<TradeRecord?>(null) }

    val todaySummary = dailySummaries.firstOrNull { it.dateKey == todayDateKey }
    val todayPnl = todaySummary?.netPnl ?: 0.0
    val todayTrades = todaySummary?.totalTrades ?: 0
    val todayWinRate = todaySummary?.winRate ?: 0.0
    val consecLosses = todaySummary?.consecutiveLosses ?: 0
    val peakDrawdown = todaySummary?.peakDrawdown ?: 0.0

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Daily P&L Card
        item {
            val isPositive = todayPnl >= 0.0
            val pnlColor = if (isPositive) tradingColors.bullish else tradingColors.bearish
            Surface(
                color = tradingColors.surfaceElevated,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, pnlColor.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "DAILY P&L SUMMARY • $todayDateKey",
                        style = MaterialTheme.typography.labelSmall,
                        color = tradingColors.cyan
                    )
                    Text(
                        text = "${if (isPositive) "+" else "-"}$${String.format("%,.2f", abs(todayPnl))}",
                        style = MaterialTheme.typography.displaySmall,
                        color = pnlColor
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatChipBox("Win Rate", "${String.format("%.1f%%", todayWinRate)}", tradingColors.bullish, Modifier.weight(1f))
                        StatChipBox("ออเดอร์วันนี้", "$todayTrades ไม้", tradingColors.cyan, Modifier.weight(1f))
                        StatChipBox("แพ้ติดกัน", "$consecLosses", if (consecLosses >= 2) tradingColors.warning else MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                        StatChipBox("Max Drawdown", "$${String.format("%.0f", peakDrawdown)}", tradingColors.bearish, Modifier.weight(1f))
                    }
                }
            }
        }

        // 7-Day Daily P&L Bar Chart
        item {
            Surface(
                color = tradingColors.surfaceElevated,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, tradingColors.cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "แนวโน้มกำไร/ขาดทุนรายวัน",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val chartData = dailySummaries.take(7).reversed()
                    if (chartData.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "ยังไม่มีข้อมูลสรุปรายวัน — เริ่มจำลองเทรดเพื่อสร้างกราฟ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .background(tradingColors.surfaceCanvas, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val w = size.width
                                val h = size.height
                                val zeroY = h / 2f
                                drawLine(
                                    color = tradingColors.cardBorder,
                                    start = Offset(0f, zeroY),
                                    end = Offset(w, zeroY),
                                    strokeWidth = 1.5f
                                )
                                val maxAbs = chartData.maxOfOrNull { abs(it.netPnl) }?.coerceAtLeast(10.0) ?: 10.0
                                val step = w / chartData.size.toFloat()
                                val barW = (step * 0.5f).coerceIn(12f, 36f)
                                chartData.forEachIndexed { idx, item ->
                                    val cx = idx * step + step / 2f
                                    val barH = ((abs(item.netPnl) / maxAbs).toFloat() * (h * 0.44f)).coerceAtLeast(4f)
                                    val top = if (item.netPnl >= 0) zeroY - barH else zeroY
                                    val col = if (item.netPnl >= 0) tradingColors.bullish else tradingColors.bearish
                                    drawRect(
                                        color = col,
                                        topLeft = Offset(cx - barW / 2f, top),
                                        size = Size(barW, barH)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Trade History Header
        item {
            Text(
                text = "ประวัติการส่งคำสั่งล่าสุด (${trades.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        if (trades.isEmpty()) {
            item {
                Surface(
                    color = tradingColors.surfaceElevated,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, tradingColors.cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Analytics,
                            contentDescription = null,
                            tint = tradingColors.cyan
                        )
                        Text(
                            text = "ยังไม่มีประวัติคำสั่งซื้อขาย",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "กลับไปที่หน้า Terminal แล้วกด SIM CALL/PUT หรือเปิด Auto-Trade เพื่อเริ่มต้น",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(trades, key = { it.id.ifBlank { "${it.instrument}_${it.entryPrice}_${it.createdAt}" } }) { trade ->
                val statusEnum = TradeStatus.safeValueOf(trade.status)
                val statusColor = when (statusEnum) {
                    TradeStatus.WON -> tradingColors.bullish
                    TradeStatus.LOST, TradeStatus.REJECTED -> tradingColors.bearish
                    TradeStatus.OPEN -> tradingColors.cyan
                    TradeStatus.CANCELLED -> tradingColors.warning
                }
                Surface(
                    color = tradingColors.surfaceElevated,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, tradingColors.cardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedTrade = trade }
                        .testTag("trade_row_${trade.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "${trade.instrument} • ${trade.direction} (${trade.timeframe})",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${trade.mode} • ${trade.strategyName} • Conf ${trade.confidenceScore}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = if (statusEnum == TradeStatus.OPEN) "OPEN"
                                else "${if (trade.pnl >= 0) "+" else "-"}$${String.format("%.2f", abs(trade.pnl))}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                            Text(
                                text = statusEnum.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor
                            )
                        }
                    }
                }
            }
        }
    }

    selectedTrade?.let { trade ->
        val timeText = trade.createdAt?.toDate()?.let {
            SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(it)
        } ?: SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date())

        AlertDialog(
            onDismissRequest = { selectedTrade = null },
            containerColor = tradingColors.surfaceElevated,
            title = { Text("${trade.instrument} • ${trade.direction}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("สถานะ: ${trade.status} (${trade.mode})")
                    Text("เวลา: $timeText")
                    Text("วงเงิน: $${String.format("%.2f", trade.amount)} | P&L: $${String.format("%.2f", trade.pnl)}")
                    Text("ราคาเข้า: ${trade.entryPrice} -> ออก: ${trade.exitPrice}")
                    Text("Calibrated Prob: ${(trade.calibratedProbability * 100).toInt()}% (Conf ${trade.confidenceScore}%)")
                    Text("เหตุผลสัญญาณ: ${trade.explanation}", color = tradingColors.cyan)
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedTrade = null }) {
                    Text("ปิด")
                }
            }
        )
    }
}

@Composable
private fun StatChipBox(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    Surface(
        color = tradingColors.surfaceCanvas,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
        }
    }
}
