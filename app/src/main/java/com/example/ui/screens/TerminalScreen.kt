package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.DriftStatus
import com.example.model.ExecutionMode
import com.example.model.ExecutionStage
import com.example.model.RiskPolicy
import com.example.model.SUPPORTED_INSTRUMENTS
import com.example.model.StrategyConfig
import com.example.model.Timeframe
import com.example.model.TradeDirection
import com.example.model.UserProfile
import com.example.ui.components.CandlestickChartPanel
import com.example.ui.components.MultiTimeframeIntelligenceCard
import com.example.ui.components.SimulatorReplayBarCard
import com.example.ui.theme.LocalTradingColors
import com.example.viewmodel.TerminalUiState

@Composable
fun TerminalScreen(
    terminalState: TerminalUiState,
    userProfile: UserProfile?,
    activeStrategy: StrategyConfig,
    riskPolicy: RiskPolicy,
    backendConnected: Boolean,
    onSelectInstrument: (String) -> Unit,
    onSelectTimeframe: (Timeframe) -> Unit,
    onToggleAutoTrade: (Boolean) -> Unit,
    onManualTrade: (TradeDirection?) -> Unit,
    onEmergencyStop: () -> Unit,
    onResetEmergencyStop: () -> Unit,
    onStartReplay: () -> Unit,
    onStepReplay: (Int) -> Unit,
    onResetReplay: () -> Unit,
    onExitReplay: () -> Unit,
    onReconcileOrders: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val mode = ExecutionMode.safeValueOf(userProfile?.executionMode)
    val balance = if (terminalState.accountMode == "PRACTICE" && terminalState.sessionVerified) {
        terminalState.brokerBalance ?: 0.0
    } else {
        userProfile?.simulationBalance ?: 10000.0
    }
    val instrumentSpec = SUPPORTED_INSTRUMENTS.firstOrNull { it.symbol == terminalState.selectedInstrument }
        ?: SUPPORTED_INSTRUMENTS.first()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Top Terminal Status & Emergency Kill Switch
        item {
            Surface(
                color = tradingColors.surfaceElevated,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, tradingColors.cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (riskPolicy.emergencyStopped) tradingColors.bearish
                                        else if (backendConnected) tradingColors.bullish
                                        else tradingColors.cyan
                                    )
                            )
                            Text(
                                text = "${mode.label} • ${terminalState.streamTransport.label}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (mode == ExecutionMode.LIVE) tradingColors.warning else tradingColors.cyan
                            )
                        }
                        val ws = terminalState.wsState
                        val activeWsChannels = listOf(ws.marketConnected, ws.signalConnected, ws.systemConnected).count { it }
                        val wsStatusLabel = when {
                            activeWsChannels > 0 -> "WS Channels $activeWsChannels/3 (Market•Signal•Sys)"
                            ws.isFallbackPolling -> "Fallback Polling (Retry #${ws.reconnectAttempt})"
                            backendConnected -> "REST Connected"
                            else -> "Local Sim Stream"
                        }
                        Text(
                            text = wsStatusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (activeWsChannels > 0) tradingColors.bullish else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("ws_channel_status_label")
                        )
                        Text(
                            text = "$${String.format("%,.2f", balance)}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (riskPolicy.emergencyStopped) {
                        Button(
                            onClick = onResetEmergencyStop,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tradingColors.warning,
                                contentColor = tradingColors.surfaceCanvas
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("reset_emergency_stop_button")
                        ) {
                            Text("ปลดล็อก Stop", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = onEmergencyStop,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tradingColors.bearish,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("emergency_stop_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = "Emergency Stop",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("KILL STOP", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 1b. Explicit account, operation, AI and research mode labels.
        item {
            Surface(
                color = tradingColors.surfaceElevated,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, tradingColors.cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("สถานะระบบและบัญชี", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("ACCOUNT: ${terminalState.accountDisplayLabel} (${terminalState.accountMode})")
                    Text("EXECUTION: ${mode.label} • REAL: LOCKED")
                    Text("CONNECTION: ${terminalState.connectionState} • Session verified: ${if (terminalState.sessionVerified) "YES" else "NO"}")
                    Text("OPERATION: ${terminalState.operationMode} • ${if (terminalState.operationMode == "AUTO") "Auto Trading" else "Manual Trading"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = false, onClick = {}, enabled = false, label = { Text("LEARNING: ${terminalState.learningStatus}") })
                        FilterChip(selected = false, onClick = {}, enabled = false, label = { Text("TRAINING: ${terminalState.trainingStatus}") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = terminalState.replayState?.active == true,
                            onClick = {},
                            enabled = false,
                            label = { Text(if (terminalState.replayState?.active == true) "REPLAY ACTIVE" else "BACKTEST: ${terminalState.backtestStatus}") }
                        )
                        FilterChip(selected = false, onClick = {}, enabled = false, label = { Text("REAL: LOCKED") })
                    }
                    Text(
                        text = when (terminalState.accountMode) {
                            "PRACTICE" -> if (terminalState.sessionVerified) "ยอดเงินด้านบนมาจาก IQ Option Practice โดยตรง" else "ยังยืนยัน Practice Session ไม่สำเร็จ — ห้ามส่งคำสั่ง"
                            "SIMULATION" -> "ยอดเงินเป็นข้อมูล Simulation ภายใน ไม่ใช่ยอดเงินโบรกเกอร์"
                            else -> "ไม่สามารถยืนยันประเภทบัญชีได้ — ระบบต้องปิดกั้นคำสั่งซื้อขาย"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 1a. Unresolved Order Reconciliation Guard Banner
        if (terminalState.unresolvedOrdersCount > 0) {
            item {
                Surface(
                    color = tradingColors.warningContainer,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, tradingColors.warning.copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "พบออเดอร์ค้าง ${terminalState.unresolvedOrdersCount} รายการ (PENDING / UNKNOWN)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = tradingColors.warning
                            )
                            Text(
                                text = "Risk Gate จะปิดออเดอร์ย้อนหลังผ่าน Reconciliation ก่อนเปิดไม้ใหม่ (ห้ามยิงซ้ำเมื่อ UNKNOWN)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = onReconcileOrders,
                            enabled = !terminalState.isReconciling,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tradingColors.warning,
                                contentColor = tradingColors.surfaceCanvas
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("reconcile_orders_button")
                        ) {
                            Text(
                                text = if (terminalState.isReconciling) "กำลังตรวจ..." else "Reconcile",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // 1c. ML Drift Monitoring Alert Banner (Alerts only; never auto-swaps model)
        if (terminalState.activeModelDriftStatus != DriftStatus.HEALTHY) {
            item {
                val isCritical = terminalState.activeModelDriftStatus == DriftStatus.DRIFT_DETECTED
                val alertColor = if (isCritical) tradingColors.bearish else tradingColors.warning
                val alertBg = if (isCritical) tradingColors.bearishContainer else tradingColors.warningContainer
                Surface(
                    color = alertBg,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, alertColor.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ml_drift_alert_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = "ML Model Drift Alert",
                            tint = alertColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "ML Drift Monitor: ${terminalState.activeModelDriftStatus.labelTh} (Score ${String.format("%.2f", terminalState.activeModelDriftScore)})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = alertColor
                            )
                            Text(
                                text = terminalState.activeModelDriftNotes.ifBlank {
                                    "ตรวจพบความเบี่ยงเบนของฟีเจอร์หรือ Win Rate ลดลง — กรุณาตรวจสอบและกด Rollback เองในแท็บ Strategy -> ML Models (ระบบไม่เปลี่ยนโมเดลอัตโนมัติ)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 2. Instrument & Timeframe Selector
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SUPPORTED_INSTRUMENTS.forEach { spec ->
                        val selected = spec.symbol == terminalState.selectedInstrument
                        val normalizedSymbol = spec.symbol.replace("/", "").uppercase()
                        val brokerItems = terminalState.assetCatalog.filter {
                            it.symbol.replace("/", "").uppercase() == normalizedSymbol &&
                                it.productType in setOf("TURBO", "BINARY")
                        }
                        val brokerAssetOpen = brokerItems.any { it.status == "OPEN" }
                        val payoutLabel = if (terminalState.accountMode == "PRACTICE") {
                            brokerItems.joinToString(" / ") { item ->
                                "${item.productType}: ${item.payoutPercent?.let { "${it.toInt()}%" } ?: "N/A"}"
                            }.ifBlank { "BROKER N/A" }
                        } else {
                            "SIM est. ${(spec.payoutRate * 100).toInt()}%"
                        }
                        FilterChip(
                            selected = selected,
                            enabled = terminalState.accountMode != "PRACTICE" ||
                                (terminalState.sessionVerified && brokerAssetOpen),
                            onClick = { onSelectInstrument(spec.symbol) },
                            label = {
                                Text(
                                    text = "${spec.symbol} • $payoutLabel",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = tradingColors.cyanContainer,
                                selectedLabelColor = tradingColors.cyan
                            ),
                            modifier = Modifier.testTag("instrument_${spec.symbol.replace("/", "_")}")
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Timeframe.entries.forEach { tf ->
                            FilterChip(
                                selected = tf == terminalState.selectedTimeframe,
                                onClick = { onSelectTimeframe(tf) },
                                label = { Text(tf.code, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = tradingColors.bullishContainer,
                                    selectedLabelColor = tradingColors.bullish
                                ),
                                modifier = Modifier.testTag("timeframe_${tf.code}")
                            )
                        }
                    }
                    Text(
                        text = "กลยุทธ์: ${activeStrategy.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 2b. Broker-backed product and asset directory.
        item {
            Surface(
                color = tradingColors.surfaceElevated,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, tradingColors.cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("MARKET & ASSET DIRECTORY", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = "แหล่งข้อมูล: ${terminalState.assetCatalogSource}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (terminalState.assetCatalog.isNotEmpty()) {
                        val openCount = terminalState.assetCatalog.count { it.status == "OPEN" }
                        val closedCount = terminalState.assetCatalog.count { it.status == "CLOSED" }
                        Text(
                            "ทั้งหมด ${terminalState.assetCatalog.size} รายการ • OPEN $openCount • CLOSED $closedCount • UNKNOWN ${terminalState.assetCatalog.size - openCount - closedCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (terminalState.assetCatalog.isEmpty()) {
                        Text(
                            "ยังไม่มีรายการสินทรัพย์จาก Broker — รายการด้านบนเป็นรายการอ้างอิงของ Simulation เท่านั้น",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        terminalState.assetCatalog.take(60).forEach { asset ->
                            val statusLabel = when (asset.status) {
                                "OPEN" -> "OPEN"
                                "CLOSED" -> "CLOSED"
                                else -> "UNKNOWN"
                            }
                            val statusColor = when (asset.status) {
                                "OPEN" -> tradingColors.bullish
                                "CLOSED" -> tradingColors.bearish
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "#${asset.rank.toString().padStart(2, '0')} • ${asset.productLabel} • ${asset.symbol}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = statusColor)
                                }
                                if (asset.displayName.isNotBlank() && !asset.displayName.equals(asset.symbol, ignoreCase = true)) {
                                    Text(asset.displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val payoutText = asset.payoutPercent?.let { "Payout ${String.format("%.2f", it)}%" } ?: "Payout N/A"
                                val stakeText = listOfNotNull(
                                    asset.minStake?.let { "Min ${String.format("%.2f", it)}" },
                                    asset.maxStake?.let { "Max ${String.format("%.2f", it)}" },
                                    asset.leverage?.let { "Leverage ${String.format("%.2f", it)}x" }
                                ).joinToString(" • ")
                                Text(
                                    listOf(payoutText, stakeText).filter { it.isNotBlank() }.joinToString(" • "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (terminalState.accountMode == "PRACTICE" && terminalState.unavailableAssetTypes.isNotEmpty()) {
                        Text(
                            "ยังไม่พบใน API: ${terminalState.unavailableAssetTypes.joinToString()} — ไม่เปิดให้ส่งคำสั่ง",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "สถานะ OPEN/CLOSED และ Payout แสดงตามข้อมูลที่ API ส่งกลับเท่านั้น หากไม่มีค่าให้แสดง N/A ไม่ใช้ค่าประมาณแทน",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 3. Interactive Candlestick + Technical Indicators Chart
        item {
            CandlestickChartPanel(
                candles = terminalState.candles,
                snapshot = terminalState.technicalSnapshot,
                decimals = instrumentSpec.decimals
            )
        }

        // 3b. Historical Simulator Replay Controls (Zero Look-Ahead)
        item {
            SimulatorReplayBarCard(
                replayState = terminalState.replayState,
                isBusy = terminalState.isReplayBusy,
                onStartReplay = onStartReplay,
                onStepReplay = onStepReplay,
                onResetReplay = onResetReplay,
                onExitReplay = onExitReplay
            )
        }

        // 3c. Multi-Timeframe Confirmation Matrix (1m Primary + 5m/15m Confirmation)
        if (terminalState.mtfReport != null) {
            item {
                MultiTimeframeIntelligenceCard(
                    mtfReport = terminalState.mtfReport,
                    dataQuality = terminalState.dataQuality
                )
            }
        }

        // 4. AI Signal & Evidence Breakdown Card
        item {
            val signal = terminalState.signalAnalysis
            val snapshot = terminalState.technicalSnapshot
            Surface(
                color = tradingColors.surfaceElevated,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, tradingColors.cardBorder),
                modifier = Modifier.fillMaxWidth()
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
                        Column {
                            Text(
                                text = "AI SIGNAL • ${signal?.sourceLabel ?: "Local Heuristic"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = tradingColors.cyan
                            )
                            Text(
                                text = snapshot?.regime?.labelTh ?: "กำลังประเมินตลาด",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        AnimatedContent(targetState = signal?.direction ?: TradeDirection.NO_TRADE, label = "dir") { dir ->
                            val (bg, fg, label) = when (dir) {
                                TradeDirection.CALL -> Triple(tradingColors.bullishContainer, tradingColors.bullish, "CALL ▲")
                                TradeDirection.PUT -> Triple(tradingColors.bearishContainer, tradingColors.bearish, "PUT ▼")
                                TradeDirection.NO_TRADE -> Triple(tradingColors.warningContainer, tradingColors.warning, "NO TRADE")
                            }
                            Surface(
                                color = bg,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, fg.copy(alpha = 0.6f))
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = fg,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    if (signal != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            MetricProgressBox(
                                title = "Model Confidence",
                                valueLabel = "${signal.confidenceScore}%",
                                progress = signal.confidenceScore / 100f,
                                barColor = tradingColors.cyan,
                                modifier = Modifier.weight(1f)
                            )
                            MetricProgressBox(
                                title = "Calibrated Win Prob.",
                                valueLabel = "${(signal.calibratedProbability * 100).toInt()}%",
                                progress = signal.calibratedProbability.toFloat(),
                                barColor = tradingColors.bullish,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (snapshot != null) {
                            Text(
                                text = "Pattern: ${snapshot.candlestickPattern} • ATR Volatility: ${String.format("%.2f%%", snapshot.volatilityPct)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                color = tradingColors.surfaceCanvas,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, tradingColors.cardBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("indicator_telemetry_row")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "ADX ${String.format("%.1f", snapshot.adx)} (+DI ${String.format("%.0f", snapshot.plusDi)}/-DI ${String.format("%.0f", snapshot.minusDi)})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = tradingColors.cyan
                                    )
                                    Text(
                                        text = "Stoch ${String.format("%.0f", snapshot.stochasticK)}/${String.format("%.0f", snapshot.stochasticD)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (snapshot.vwapReliable) {
                                            "VWAP ${if (snapshot.vwap >= 100.0) String.format("%.2f", snapshot.vwap) else String.format("%.5f", snapshot.vwap)}"
                                        } else {
                                            "VWAP N/A"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (snapshot.vwapReliable) tradingColors.bullish else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (signal.supportingEvidence.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "หลักฐานสนับสนุน",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = tradingColors.bullish
                                )
                                signal.supportingEvidence.take(3).forEach { ev ->
                                    Text(
                                        text = "• $ev",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        if (signal.contradictingEvidence.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "ข้อควรระวัง / สัญญาณคัดค้าน",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = tradingColors.warning
                                )
                                signal.contradictingEvidence.take(2).forEach { ev ->
                                    Text(
                                        text = "• $ev",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Text(
                            text = signal.riskAssessment,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 5. Automated Execution Engine & Risk Gate Card
        item {
            val risk = terminalState.riskValidation
            Surface(
                color = tradingColors.surfaceElevated,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    1.dp,
                    if (risk.allowed) tradingColors.cardBorder else tradingColors.bearish.copy(alpha = 0.55f)
                ),
                modifier = Modifier.fillMaxWidth()
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
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bolt,
                                contentDescription = "Auto Trade",
                                tint = if (riskPolicy.autoTradeEnabled) tradingColors.bullish else tradingColors.cyan
                            )
                            Column {
                                Text(
                                    text = "Auto-Trading Engine (${mode.label})",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "วงเงิน/ไม้ $${String.format("%.0f", activeStrategy.tradeAmount)} • เหลือวันนี้ ${risk.remainingDailyTrades} ไม้",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Switch(
                            checked = riskPolicy.autoTradeEnabled,
                            onCheckedChange = onToggleAutoTrade,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = tradingColors.surfaceCanvas,
                                checkedTrackColor = tradingColors.bullish
                            ),
                            modifier = Modifier.testTag("auto_trade_switch")
                        )
                    }

                    // 11-stage Pipeline Status Indicator
                    Surface(
                        color = tradingColors.surfaceCanvas,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Execution Lifecycle (${terminalState.executionStage.stepNumber}/11)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tradingColors.cyan
                                )
                                Text(
                                    text = terminalState.executionStage.titleTh,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            LinearProgressIndicator(
                                progress = {
                                    if (terminalState.executionStage == ExecutionStage.IDLE) 0f
                                    else terminalState.executionStage.stepNumber / 11f
                                },
                                color = tradingColors.bullish,
                                trackColor = tradingColors.cardBorder,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            if (!terminalState.lastOrderCorrelationId.isNullOrBlank()) {
                                Text(
                                    text = "Correlation: ${terminalState.lastOrderCorrelationId} • Lifecycle: ${terminalState.lastOrderLifecycleState?.name ?: "SETTLED"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Risk Guard status row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (risk.allowed) Icons.Rounded.CheckCircle else Icons.Rounded.Shield,
                            contentDescription = "Risk Status",
                            tint = if (risk.allowed) tradingColors.bullish else tradingColors.bearish,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = risk.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (risk.allowed) tradingColors.bullish else tradingColors.bearish
                        )
                    }

                    // Manual Execution Controls (gated by Risk Engine)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onManualTrade(TradeDirection.CALL) },
                            enabled = !terminalState.isOrderInFlight && !riskPolicy.emergencyStopped,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tradingColors.bullish,
                                contentColor = tradingColors.surfaceCanvas
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("manual_call_button")
                        ) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("SIM CALL", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { onManualTrade(TradeDirection.PUT) },
                            enabled = !terminalState.isOrderInFlight && !riskPolicy.emergencyStopped,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tradingColors.bearish,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("manual_put_button")
                        ) {
                            Icon(Icons.Rounded.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("SIM PUT", fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = { onManualTrade(null) },
                        enabled = !terminalState.isOrderInFlight && !riskPolicy.emergencyStopped,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, tradingColors.cyan.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("run_ai_cycle_button")
                    ) {
                        Icon(Icons.Rounded.PauseCircle, contentDescription = null, tint = tradingColors.cyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "รันรอบวิเคราะห์ AI + ส่งคำสั่งตามสัญญาณ",
                            color = tradingColors.cyan,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricProgressBox(
    title: String,
    valueLabel: String,
    progress: Float,
    barColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    Surface(
        color = tradingColors.surfaceCanvas,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = barColor)
            }
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                color = barColor,
                trackColor = tradingColors.cardBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
    }
}
