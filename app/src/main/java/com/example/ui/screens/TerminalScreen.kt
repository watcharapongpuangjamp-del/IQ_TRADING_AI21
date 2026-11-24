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
import com.example.model.ExecutionMode
import com.example.model.ExecutionStage
import com.example.model.RiskPolicy
import com.example.model.SUPPORTED_INSTRUMENTS
import com.example.model.StrategyConfig
import com.example.model.Timeframe
import com.example.model.TradeDirection
import com.example.model.UserProfile
import com.example.ui.components.CandlestickChartPanel
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
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val mode = ExecutionMode.safeValueOf(userProfile?.executionMode)
    val balance = userProfile?.simulationBalance ?: 10000.0
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
                                text = "${mode.label} • ${if (backendConnected) "FastAPI Connected" else "Built-in Sim"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (mode == ExecutionMode.LIVE) tradingColors.warning else tradingColors.cyan
                            )
                        }
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
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectInstrument(spec.symbol) },
                            label = {
                                Text(
                                    text = "${spec.symbol} (${(spec.payoutRate * 100).toInt()}%)",
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

        // 3. Interactive Candlestick + Technical Indicators Chart
        item {
            CandlestickChartPanel(
                candles = terminalState.candles,
                snapshot = terminalState.technicalSnapshot,
                decimals = instrumentSpec.decimals
            )
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
                                text = "AI SIGNAL INTELLIGENCE",
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
