package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoGraph
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ModelTraining
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.example.model.DriftStatus
import com.example.model.MlModelSummary
import com.example.ui.theme.LocalTradingColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MlGovernancePanel(
    instrument: String,
    backendConnected: Boolean,
    mlModels: List<MlModelSummary>,
    activeModel: MlModelSummary?,
    isBusy: Boolean,
    onRefreshModels: () -> Unit,
    onTrainModel: (String) -> Unit,
    onPromoteModel: (String) -> Unit,
    onCheckDrift: (String) -> Unit = {},
    onRollbackModel: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    var selectedAlgorithm by remember { mutableStateOf("logistic_regression") }
    var pendingPromoteModel by remember { mutableStateOf<MlModelSummary?>(null) }
    var pendingRollbackModel by remember { mutableStateOf<MlModelSummary?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Train & Walk-Forward Validation Card
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.ModelTraining, contentDescription = null, tint = tradingColors.cyan)
                        Column {
                            Text(
                                text = "ML Training & Calibration ($instrument)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (backendConnected) "Walk-Forward Out-of-Sample Gate • 17 Causal Features" else "ต้องเชื่อมต่อ FastAPI Backend เพื่อเทรนโมเดล",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onRefreshModels,
                        enabled = backendConnected && !isBusy,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("refresh_ml_models_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh ML Models", modifier = Modifier.size(18.dp))
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedAlgorithm == "logistic_regression",
                        onClick = { selectedAlgorithm = "logistic_regression" },
                        label = { Text("Logistic + Platt") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tradingColors.cyanContainer,
                            selectedLabelColor = tradingColors.cyan
                        ),
                        modifier = Modifier.testTag("algo_logreg_chip")
                    )
                    FilterChip(
                        selected = selectedAlgorithm == "hist_gradient_boosting",
                        onClick = { selectedAlgorithm = "hist_gradient_boosting" },
                        label = { Text("HistGBM + Platt") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tradingColors.cyanContainer,
                            selectedLabelColor = tradingColors.cyan
                        ),
                        modifier = Modifier.testTag("algo_hgbm_chip")
                    )
                    FilterChip(
                        selected = selectedAlgorithm == "random_forest",
                        onClick = { selectedAlgorithm = "random_forest" },
                        label = { Text("Random Forest + Platt") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tradingColors.cyanContainer,
                            selectedLabelColor = tradingColors.cyan
                        ),
                        modifier = Modifier.testTag("algo_rf_chip")
                    )
                    FilterChip(
                        selected = selectedAlgorithm == "xgb_boosting",
                        onClick = { selectedAlgorithm = "xgb_boosting" },
                        label = { Text("XGBoost + Platt") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tradingColors.cyanContainer,
                            selectedLabelColor = tradingColors.cyan
                        ),
                        modifier = Modifier.testTag("algo_xgb_chip")
                    )
                }

                Button(
                    onClick = { onTrainModel(selectedAlgorithm) },
                    enabled = backendConnected && !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tradingColors.cyan,
                        contentColor = tradingColors.surfaceCanvas
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("train_ml_model_button")
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = tradingColors.surfaceCanvas,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("กำลังประมวลผล ML Pipeline...", fontWeight = FontWeight.Bold)
                    } else {
                        Text("Train & Walk-Forward Validate", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 2. Active Inference Model & Drift Monitoring Card
        if (activeModel != null) {
            val driftColor = when (activeModel.driftStatus) {
                DriftStatus.HEALTHY -> tradingColors.bullish
                DriftStatus.WARNING -> tradingColors.warning
                DriftStatus.DRIFT_DETECTED -> tradingColors.bearish
            }
            val driftBg = when (activeModel.driftStatus) {
                DriftStatus.HEALTHY -> tradingColors.bullishContainer
                DriftStatus.WARNING -> tradingColors.warningContainer
                DriftStatus.DRIFT_DETECTED -> tradingColors.bearishContainer
            }

            Surface(
                color = tradingColors.surfaceElevated,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, driftColor.copy(alpha = 0.65f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("active_ml_model_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.AutoGraph, contentDescription = null, tint = tradingColors.bullish)
                            Text(
                                text = "Active: ${activeModel.algorithm}",
                                style = MaterialTheme.typography.titleSmall,
                                color = tradingColors.bullish
                            )
                        }
                        Surface(
                            color = driftBg,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, driftColor.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = "${activeModel.driftStatus.name} (${String.format("%.2f", activeModel.driftScore)})",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = driftColor,
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .testTag("active_ml_drift_badge")
                            )
                        }
                    }

                    Text(
                        text = "Dataset: ${activeModel.datasetVersion} (${activeModel.featureCount} features)",
                        style = MaterialTheme.typography.labelSmall,
                        color = tradingColors.cyan
                    )
                    Text(
                        text = "OOS Acc: ${(activeModel.outOfSampleAccuracy * 100).toInt()}% • Brier: ${String.format("%.4f", activeModel.brierScore)} • ECE: ${String.format("%.4f", activeModel.ece)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Backtest: ${activeModel.simulatedTrades} trades • Win ${(activeModel.simulatedWinRate * 100).toInt()}% • Settled Live: ${activeModel.liveSettledTrades} (Win ${(activeModel.liveWinRate * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (activeModel.driftNotes.isNotBlank()) {
                        Text(
                            text = activeModel.driftNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (activeModel.driftStatus == DriftStatus.HEALTHY) MaterialTheme.colorScheme.onSurfaceVariant else driftColor
                        )
                    }
                    if (activeModel.driftStatus == DriftStatus.DRIFT_DETECTED) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.WarningAmber,
                                contentDescription = "Drift alert",
                                tint = tradingColors.bearish,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "พบ Drift / ประสิทธิภาพลดลง — ระบบจะไม่เปลี่ยนโมเดลอัตโนมัติ กรุณากด Rollback ด้านล่างหากต้องการย้อนเวอร์ชัน",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = tradingColors.bearish
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { onCheckDrift(activeModel.modelId) },
                        enabled = backendConnected && !isBusy,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, driftColor.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("check_ml_drift_button")
                    ) {
                        Text(
                            text = "ตรวจ Drift & Settled Performance ทันที",
                            color = driftColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 3. Model Registry Items
        if (mlModels.isEmpty()) {
            Surface(
                color = tradingColors.surfaceElevated,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, tradingColors.cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (backendConnected) {
                        "ยังไม่มีโมเดลใน Registry สำหรับ $instrument — กด Train ด้านบนเพื่อสร้างโมเดลแรก"
                    } else {
                        "เมื่อเชื่อมต่อ FastAPI Backend รายการโมเดลและผล Walk-Forward Validation จะปรากฏที่นี่"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            mlModels.forEach { model ->
                val isValidated = model.status.equals("VALIDATED", ignoreCase = true)
                val badgeColor = when {
                    model.isActive -> tradingColors.bullish
                    isValidated -> tradingColors.cyan
                    else -> tradingColors.bearish
                }
                Surface(
                    color = tradingColors.surfaceElevated,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, tradingColors.cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${model.algorithm} (${model.modelId.take(8)})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (model.isActive) "ACTIVE" else model.status,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor
                            )
                        }
                        Text(
                            text = "Dataset ${model.datasetVersion} (${model.featureCount} features) • Drift ${model.driftStatus.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = tradingColors.cyan
                        )
                        Text(
                            text = "Acc ${(model.outOfSampleAccuracy * 100).toInt()}% | Brier ${String.format("%.4f", model.brierScore)} | ECE ${String.format("%.4f", model.ece)} | Sim Net $${String.format("%.2f", model.simulatedNetPnl)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!model.rolledBackFromModelId.isNullOrBlank()) {
                            Text(
                                text = "Rolled back from: ${model.rolledBackFromModelId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = tradingColors.warning
                            )
                        }
                        Text(
                            text = model.validationNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (isValidated && !model.isActive) {
                            if (activeModel != null) {
                                OutlinedButton(
                                    onClick = { pendingRollbackModel = model },
                                    enabled = backendConnected && !isBusy,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, tradingColors.warning.copy(alpha = 0.7f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("rollback_model_${model.modelId}")
                                ) {
                                    Icon(
                                        Icons.Rounded.History,
                                        contentDescription = "Rollback model",
                                        tint = tradingColors.warning,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Rollback เป็นเวอร์ชันนี้ (Owner Only)",
                                        color = tradingColors.warning,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { pendingPromoteModel = model },
                                    enabled = backendConnected && !isBusy,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("promote_model_${model.modelId}")
                                ) {
                                    Text("Promote เป็นโมเดลใช้งานจริง (Owner Only)")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val confirmTarget = pendingPromoteModel
    if (confirmTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingPromoteModel = null },
            title = { Text("ยืนยันการ Promote โมเดล ML") },
            text = {
                Text(
                    "ต้องการเปิดใช้งานโมเดล ${confirmTarget.algorithm} (${confirmTarget.modelId.take(8)}) สำหรับ Inference จริงหรือไม่?\n\nDataset: ${confirmTarget.datasetVersion}\nOut-of-Sample Accuracy: ${(confirmTarget.outOfSampleAccuracy * 100).toInt()}%\nBrier Score: ${String.format("%.4f", confirmTarget.brierScore)}"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingPromoteModel = null
                        onPromoteModel(confirmTarget.modelId)
                    }
                ) {
                    Text("ยืนยัน Promote")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPromoteModel = null }) {
                    Text("ยกเลิก")
                }
            }
        )
    }

    val rollbackTarget = pendingRollbackModel
    if (rollbackTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingRollbackModel = null },
            title = { Text("ยืนยัน Manual Model Rollback") },
            text = {
                Text(
                    "ต้องการ Rollback จากโมเดลปัจจุบัน ${activeModel?.algorithm ?: "-"} (${activeModel?.modelId?.take(8) ?: "-"}) กลับไปใช้ ${rollbackTarget.algorithm} (${rollbackTarget.modelId.take(8)}) หรือไม่?\n\nDataset: ${rollbackTarget.datasetVersion}\nOOS Accuracy: ${(rollbackTarget.outOfSampleAccuracy * 100).toInt()}%\nBrier Score: ${String.format("%.4f", rollbackTarget.brierScore)}"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRollbackModel = null
                        onRollbackModel(rollbackTarget.modelId)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tradingColors.warning,
                        contentColor = tradingColors.surfaceCanvas
                    )
                ) {
                    Text("ยืนยัน Rollback", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRollbackModel = null }) {
                    Text("ยกเลิก")
                }
            }
        )
    }
}
