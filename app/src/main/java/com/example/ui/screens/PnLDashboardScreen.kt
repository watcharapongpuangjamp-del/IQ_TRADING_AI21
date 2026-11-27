package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.engine.TradeJournalExporter
import com.example.model.BackendOrderSummary
import com.example.model.DailyPnLSummary
import com.example.model.OrderLifecycleEvent
import com.example.model.OrderLifecycleState
import com.example.model.PerformanceAnalyticsReport
import com.example.model.TradeRecord
import com.example.model.TradeStatus
import com.example.ui.components.PerformanceAnalyticsPanel
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
    performanceReport: PerformanceAnalyticsReport = PerformanceAnalyticsReport(),
    analyticsModeFilter: String = "SIMULATION",
    onSelectAnalyticsMode: (String) -> Unit = {},
    backendOrders: List<BackendOrderSummary> = emptyList(),
    selectedOrderEvents: List<OrderLifecycleEvent> = emptyList(),
    isReconciling: Boolean = false,
    onReconcileOrders: (String?) -> Unit = {},
    onSelectBackendOrder: (String) -> Unit = {},
    onClearOrderEvents: () -> Unit = {},
    onExportFeedback: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tradingColors = LocalTradingColors.current
    var selectedTrade by remember { mutableStateOf<TradeRecord?>(null) }
    var selectedBackendOrder by remember { mutableStateOf<BackendOrderSummary?>(null) }
    var journalQuery by remember { mutableStateOf("") }
    var journalStatusFilter by remember { mutableStateOf(TradeJournalExporter.FILTER_ALL) }

    val filteredTrades = remember(trades, journalQuery, journalStatusFilter) {
        TradeJournalExporter.filterTrades(trades, journalQuery, journalStatusFilter)
    }

    fun writeUriUtf8(uri: Uri, content: String, label: String) {
        runCatching {
            val stream = context.contentResolver.openOutputStream(uri)
                ?: error("ไม่สามารถเปิดไฟล์ปลายทางได้")
            stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        }.onSuccess {
            onExportFeedback("ส่งออกสมุดบันทึก $label (${filteredTrades.size} รายการ) สำเร็จ")
        }.onFailure { err ->
            onExportFeedback("ส่งออก $label ไม่สำเร็จ: ${err.localizedMessage}")
        }
    }

    val csvExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            writeUriUtf8(uri, TradeJournalExporter.toCsv(filteredTrades), "CSV")
        }
    }

    val jsonExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            writeUriUtf8(uri, TradeJournalExporter.toJson(filteredTrades), "JSON")
        }
    }

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

        // Phase 8: Mode-Isolated Performance Analytics & Breakdowns
        item {
            PerformanceAnalyticsPanel(
                report = performanceReport,
                selectedModeFilter = analyticsModeFilter,
                onSelectModeFilter = onSelectAnalyticsMode
            )
        }

        // Backend Execution Lifecycle & Reconciliation Section
        if (backendOrders.isNotEmpty()) {
            item {
                val unresolvedCount = backendOrders.count { it.requiresReconciliation }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Execution Lifecycle (${backendOrders.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = if (unresolvedCount > 0) "ค้างตรวจสอบ $unresolvedCount รายการ" else "ออเดอร์ Backend ซิงค์ครบถ้วน",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (unresolvedCount > 0) tradingColors.warning else tradingColors.bullish
                        )
                    }
                    if (unresolvedCount > 0) {
                        Button(
                            onClick = { onReconcileOrders(null) },
                            enabled = !isReconciling,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tradingColors.warning,
                                contentColor = tradingColors.surfaceCanvas
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text(if (isReconciling) "กำลังตรวจ..." else "Reconcile All", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            items(backendOrders.take(10), key = { "be_${it.orderId}" }) { order ->
                val badgeColor = when (order.lifecycleState) {
                    OrderLifecycleState.SETTLED -> if (order.pnl >= 0) tradingColors.bullish else tradingColors.bearish
                    OrderLifecycleState.PENDING -> tradingColors.warning
                    OrderLifecycleState.UNKNOWN, OrderLifecycleState.REJECTED, OrderLifecycleState.FAILED -> tradingColors.bearish
                    else -> tradingColors.cyan
                }
                Surface(
                    color = tradingColors.surfaceElevated,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (order.requiresReconciliation) tradingColors.warning.copy(alpha = 0.65f) else tradingColors.cardBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedBackendOrder = order
                            onSelectBackendOrder(order.orderId)
                        }
                        .testTag("backend_order_${order.orderId}")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${order.orderId} • ${order.lifecycleState.name}",
                                style = MaterialTheme.typography.labelLarge,
                                color = badgeColor,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Corr: ${order.correlationId.ifBlank { "-" }} • ${order.message.take(48)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (order.requiresReconciliation) order.lifecycleState.name
                            else "${if (order.pnl >= 0) "+" else "-"}$${String.format("%.2f", abs(order.pnl))}",
                            style = MaterialTheme.typography.labelLarge,
                            color = badgeColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Trade Journal Header, Filter & Zero-Permission Export
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "สมุดบันทึกการเทรด (${filteredTrades.size}/${trades.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                csvExportLauncher.launch("iq_trade_journal_$todayDateKey.csv")
                            },
                            enabled = filteredTrades.isNotEmpty(),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("export_journal_csv_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = "ส่งออก CSV",
                                tint = tradingColors.cyan
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("CSV", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = {
                                jsonExportLauncher.launch("iq_trade_journal_$todayDateKey.json")
                            },
                            enabled = filteredTrades.isNotEmpty(),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("export_journal_json_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = "ส่งออก JSON",
                                tint = tradingColors.bullish
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("JSON", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                OutlinedTextField(
                    value = journalQuery,
                    onValueChange = { journalQuery = it },
                    singleLine = true,
                    placeholder = { Text("ค้นหาคู่เงิน, กลยุทธ์, Regime...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "ค้นหาสมุดบันทึก"
                        )
                    },
                    trailingIcon = {
                        if (journalQuery.isNotEmpty()) {
                            IconButton(onClick = { journalQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Clear,
                                    contentDescription = "ล้างคำค้นหา"
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("journal_search_input")
                )

                val statusOptions = listOf(
                    TradeJournalExporter.FILTER_ALL to "All",
                    TradeJournalExporter.FILTER_WON to "WON",
                    TradeJournalExporter.FILTER_LOST to "LOST",
                    TradeJournalExporter.FILTER_OPEN to "OPEN"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    statusOptions.forEach { (code, label) ->
                        FilterChip(
                            selected = journalStatusFilter == code,
                            onClick = { journalStatusFilter = code },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = tradingColors.cyanContainer,
                                selectedLabelColor = tradingColors.cyan
                            ),
                            modifier = Modifier.testTag("journal_filter_${code.lowercase()}")
                        )
                    }
                }
            }
        }

        if (filteredTrades.isEmpty()) {
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
                            text = if (trades.isEmpty()) "ยังไม่มีประวัติคำสั่งซื้อขาย" else "ไม่พบรายการที่ตรงกับตัวกรอง",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (trades.isEmpty()) {
                                "กลับไปที่หน้า Terminal แล้วกด SIM CALL/PUT หรือเปิด Auto-Trade เพื่อเริ่มต้น"
                            } else {
                                "ลองเปลี่ยนคำค้นหาหรือเลือกตัวกรอง All เพื่อดูรายการทั้งหมด"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(filteredTrades, key = { it.id.ifBlank { "${it.instrument}_${it.entryPrice}_${it.createdAtMillis}" } }) { trade ->
                val statusEnum = TradeStatus.safeValueOf(trade.status)
                val accountModeLabel = when (trade.mode.uppercase()) {
                    "DEMO", "PRACTICE" -> "PRACTICE"
                    "LIVE", "REAL" -> "REAL (LOCKED)"
                    "SIMULATION" -> "SIMULATION"
                    else -> trade.mode.uppercase()
                }
                val payoutLabel = if (accountModeLabel == "SIMULATION") "SIM est. payout" else "Recorded payout"
                val statusColor = when (statusEnum) {
                    TradeStatus.WON -> tradingColors.bullish
                    TradeStatus.LOST, TradeStatus.REJECTED, TradeStatus.FAILED -> tradingColors.bearish
                    TradeStatus.OPEN -> tradingColors.cyan
                    TradeStatus.CANCELLED, TradeStatus.PENDING, TradeStatus.UNKNOWN -> tradingColors.warning
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
                                text = "${accountModeLabel} • Stake ${String.format("%.2f", trade.amount)} • ${payoutLabel} ${String.format("%.2f", trade.payoutRate * 100)}% • ${trade.strategyName} • Conf ${trade.confidenceScore}%",
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
        val timeText = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(
            Date(if (trade.createdAtMillis > 0L) trade.createdAtMillis else System.currentTimeMillis())
        )

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

    selectedBackendOrder?.let { order ->
        AlertDialog(
            onDismissRequest = {
                selectedBackendOrder = null
                onClearOrderEvents()
            },
            containerColor = tradingColors.surfaceElevated,
            title = { Text("Order ${order.orderId} • ${order.lifecycleState.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Correlation ID: ${order.correlationId.ifBlank { "-" }}")
                    Text("Broker Order ID: ${order.brokerOrderId ?: "-"}")
                    Text("สถานะ: ${order.lifecycleState.labelTh} (${order.status})")
                    Text("เข้า: ${order.entryPrice} -> ออก: ${order.exitPrice} | P&L: $${String.format("%.2f", order.pnl)}")
                    if (selectedOrderEvents.isNotEmpty()) {
                        Text("Audit Trail:", fontWeight = FontWeight.Bold, color = tradingColors.cyan)
                        selectedOrderEvents.forEach { evt ->
                            Text(
                                text = "• ${evt.fromState ?: "START"} -> ${evt.toState}: ${evt.detail}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            dismissButton = {
                if (order.requiresReconciliation) {
                    OutlinedButton(
                        onClick = {
                            onReconcileOrders(order.orderId)
                            selectedBackendOrder = null
                            onClearOrderEvents()
                        }
                    ) {
                        Text("Reconcile Order")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedBackendOrder = null
                        onClearOrderEvents()
                    }
                ) {
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
