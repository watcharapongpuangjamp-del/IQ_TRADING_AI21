package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.BackendHealthState
import com.example.model.BrokerDryRunReport
import com.example.model.DiagnosticCheckStatus
import com.example.model.ExecutionMode
import com.example.model.LiveReadinessReport
import com.example.model.RiskPolicy
import com.example.model.SystemDiagnosticsReport
import com.example.ui.theme.LocalTradingColors

@Composable
fun DiagnosticsLiveGatePanel(
    liveReadiness: LiveReadinessReport,
    diagnosticsReport: SystemDiagnosticsReport?,
    isDiagnosticsBusy: Boolean,
    riskPolicy: RiskPolicy,
    backendHealth: BackendHealthState,
    onRunDiagnostics: () -> Unit,
    onSwitchMode: (mode: ExecutionMode, explicitLiveConfirmation: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    dryRunReport: BrokerDryRunReport? = null,
    isDryRunBusy: Boolean = false,
    onRunBrokerDryRun: () -> Unit = {}
) {
    val tradingColors = LocalTradingColors.current
    var showConfirmLiveDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 5-Step Live Readiness Gate Card
        Surface(
            color = tradingColors.surfaceElevated,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(
                1.dp,
                if (liveReadiness.currentMode == ExecutionMode.LIVE) tradingColors.warning else tradingColors.cardBorder
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("live_readiness_gate_card")
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = "Live Readiness Gate",
                            tint = if (liveReadiness.prerequisitesReady) tradingColors.bullish else tradingColors.warning
                        )
                        Column {
                            Text(
                                text = "5-Step Live Readiness Gate",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "ค่าเริ่มต้นล็อกที่ SIMULATION จนกว่าครบทั้ง 5 ขั้นตอน",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        color = if (liveReadiness.currentMode == ExecutionMode.LIVE) {
                            tradingColors.warningContainer
                        } else {
                            tradingColors.cyanContainer
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = liveReadiness.currentMode.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (liveReadiness.currentMode == ExecutionMode.LIVE) tradingColors.warning else tradingColors.cyan,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                liveReadiness.gates.forEach { gate ->
                    Surface(
                        color = tradingColors.surfaceCanvas,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (gate.passed) tradingColors.bullish.copy(alpha = 0.4f) else tradingColors.cardBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("live_gate_${gate.gateId}")
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (gate.passed) Icons.Rounded.CheckCircle else Icons.Rounded.Lock,
                                contentDescription = if (gate.passed) "Passed" else "Locked",
                                tint = if (gate.passed) tradingColors.bullish else tradingColors.warning,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = gate.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = gate.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = if (gate.passed) "PASS" else "LOCKED",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (gate.passed) tradingColors.bullish else tradingColors.warning,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (liveReadiness.currentMode == ExecutionMode.LIVE) {
                    Button(
                        onClick = { onSwitchMode(ExecutionMode.SIMULATION, false) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tradingColors.cyan,
                            contentColor = tradingColors.surfaceCanvas
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("revert_to_simulation_button")
                    ) {
                        Text("สลับกลับสู่โหมด SIMULATION (Safe Default)", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = { showConfirmLiveDialog = true },
                        enabled = liveReadiness.prerequisitesReady,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tradingColors.warning,
                            contentColor = tradingColors.surfaceCanvas
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("enable_live_mode_button")
                    ) {
                        Text(
                            text = if (liveReadiness.prerequisitesReady) {
                                "เปิดโหมด LIVE (ขั้นตอนที่ 5: ยืนยัน 2 ชั้น)"
                            } else {
                                "LIVE ถูกล็อก (ยังไม่ผ่านเงื่อนไข 1-4)"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 2. System Diagnostics Self-Checks Card
        Surface(
            color = tradingColors.surfaceElevated,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, tradingColors.cardBorder),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("system_diagnostics_card")
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = "System Diagnostics",
                            tint = tradingColors.cyan
                        )
                        Column {
                            Text(
                                text = "System Diagnostics Self-Checks",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = diagnosticsReport?.let {
                                    "ผลรวม: ${it.overallStatus.label} • Connector: ${it.connectorName}"
                                } ?: "ตรวจ Room SQLite, Keystore AES, Risk Guard, Data & WS",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    diagnosticsReport?.let { diag ->
                        val statusColor = when (diag.overallStatus) {
                            DiagnosticCheckStatus.PASS -> tradingColors.bullish
                            DiagnosticCheckStatus.WARN -> tradingColors.warning
                            DiagnosticCheckStatus.FAIL -> tradingColors.bearish
                        }
                        Surface(
                            color = statusColor.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = diag.overallStatus.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = onRunDiagnostics,
                    enabled = !isDiagnosticsBusy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("run_diagnostics_button")
                ) {
                    Text(
                        text = if (isDiagnosticsBusy) "กำลังตรวจสอบระบบ..." else "รัน System Diagnostics",
                        fontWeight = FontWeight.SemiBold,
                        color = tradingColors.cyan
                    )
                }

                OutlinedButton(
                    onClick = onRunBrokerDryRun,
                    enabled = !isDryRunBusy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("run_broker_dry_run_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.NetworkCheck,
                        contentDescription = "Broker Dry-Run Check",
                        tint = tradingColors.bullish,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isDryRunBusy) "กำลังทดสอบ Broker Dry-Run..." else "รัน Broker Dry-Run Check (Non-Executing)",
                        fontWeight = FontWeight.SemiBold,
                        color = tradingColors.bullish
                    )
                }

                AnimatedVisibility(visible = dryRunReport != null) {
                    dryRunReport?.let { report ->
                        val statusColor = when {
                            report.dryRunPassed && report.connected -> tradingColors.bullish
                            report.dryRunPassed -> tradingColors.cyan
                            else -> tradingColors.bearish
                        }
                        Surface(
                            color = tradingColors.surfaceCanvas,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("broker_dry_run_result_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Broker Dry-Run • ${report.connectorName}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Surface(
                                        color = statusColor.copy(alpha = 0.16f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = if (report.simulationLocked) "SIM LOCKED" else "LIVE ARMED",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = statusColor,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "Latency: ${String.format("%.1f", report.latencyMs)} ms • Price: ${String.format("%.5f", report.marketPrice)} • Balance: $${String.format("%.2f", report.balance)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tradingColors.cyan
                                )
                                if (report.capabilities.isNotEmpty()) {
                                    Text(
                                        text = "Capabilities: ${report.capabilities.joinToString(", ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = report.statusDetail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                diagnosticsReport?.checks?.forEach { item ->
                    val (icon, color) = when (item.status) {
                        DiagnosticCheckStatus.PASS -> Icons.Rounded.CheckCircle to tradingColors.bullish
                        DiagnosticCheckStatus.WARN -> Icons.Rounded.Warning to tradingColors.warning
                        DiagnosticCheckStatus.FAIL -> Icons.Rounded.Error to tradingColors.bearish
                    }
                    Surface(
                        color = tradingColors.surfaceCanvas,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, tradingColors.cardBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("diag_check_${item.checkId}")
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = item.status.label,
                                tint = color,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = item.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = item.status.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = color,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showConfirmLiveDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmLiveDialog = false },
            containerColor = tradingColors.surfaceElevated,
            title = {
                Text(
                    text = "ยืนยันเปิดโหมด LIVE Trading",
                    color = tradingColors.warning,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("คำสั่งซื้อขายในโหมด LIVE จะส่งไปยัง Connector จริง: ${backendHealth.connectorName}")
                    Text("เพดานความเสี่ยงต่อไม้: $${riskPolicy.maxTradeAmount.toInt()}")
                    Text("เพดานขาดทุนสูงสุดต่อวัน: $${riskPolicy.maxDailyLoss.toInt()}")
                    Text("จำกัดออเดอร์สูงสุดต่อวัน: ${riskPolicy.maxDailyTrades} ไม้")
                    Text(
                        text = "คุณยืนยันว่าตรวจสอบผล Backtest และ Risk Guardrail เรียบร้อยแล้วใช่หรือไม่?",
                        color = tradingColors.cyan
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmLiveDialog = false }) {
                    Text("ยกเลิก (คงไว้ SIMULATION)")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmLiveDialog = false
                        onSwitchMode(ExecutionMode.LIVE, true)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tradingColors.warning,
                        contentColor = tradingColors.surfaceCanvas
                    ),
                    modifier = Modifier.testTag("confirm_live_mode_dialog_button")
                ) {
                    Text("ยืนยันเปิดโหมด LIVE", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}
