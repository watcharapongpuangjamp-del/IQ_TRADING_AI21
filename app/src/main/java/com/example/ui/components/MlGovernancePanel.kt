package com.example.ui.components

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
import androidx.compose.material.icons.rounded.AutoGraph
import androidx.compose.material.icons.rounded.ModelTraining
import androidx.compose.material.icons.rounded.Refresh
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
import com.example.model.MlModelSummary
import com.example.ui.theme.LocalTradingColors

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
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    var selectedAlgorithm by remember { mutableStateOf("logistic_regression") }
    var pendingPromoteModel by remember { mutableStateOf<MlModelSummary?>(null) }

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
                                text = if (backendConnected) "Walk-Forward Out-of-Sample Gate" else "ต้องเชื่อมต่อ FastAPI Backend เพื่อเทรนโมเดล",
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        Text("กำลังเทรนและตรวจ Walk-Forward...", fontWeight = FontWeight.Bold)
                    } else {
                        Text("Train & Walk-Forward Validate", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 2. Active Inference Model Summary
        if (activeModel != null) {
            Surface(
                color = tradingColors.surfaceElevated,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, tradingColors.bullish.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.AutoGraph, contentDescription = null, tint = tradingColors.bullish)
                        Text(
                            text = "Active Inference Model: ${activeModel.algorithm}",
                            style = MaterialTheme.typography.titleSmall,
                            color = tradingColors.bullish
                        )
                    }
                    Text(
                        text = "OOS Acc: ${(activeModel.outOfSampleAccuracy * 100).toInt()}% • Brier: ${String.format("%.4f", activeModel.brierScore)} • ECE: ${String.format("%.4f", activeModel.ece)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Backtest: ${activeModel.simulatedTrades} trades • Win ${(activeModel.simulatedWinRate * 100).toInt()}% • Net $${String.format("%.2f", activeModel.simulatedNetPnl)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                            text = "Acc ${(model.outOfSampleAccuracy * 100).toInt()}% | Brier ${String.format("%.4f", model.brierScore)} | ECE ${String.format("%.4f", model.ece)} | Sim Net $${String.format("%.2f", model.simulatedNetPnl)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = model.validationNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (isValidated && !model.isActive) {
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

    val confirmTarget = pendingPromoteModel
    if (confirmTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingPromoteModel = null },
            title = { Text("ยืนยันการ Promote โมเดล ML") },
            text = {
                Text(
                    "ต้องการเปิดใช้งานโมเดล ${confirmTarget.algorithm} (${confirmTarget.modelId.take(8)}) สำหรับ Inference จริงหรือไม่?\n\nOut-of-Sample Accuracy: ${(confirmTarget.outOfSampleAccuracy * 100).toInt()}%\nBrier Score: ${String.format("%.4f", confirmTarget.brierScore)}"
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
}
