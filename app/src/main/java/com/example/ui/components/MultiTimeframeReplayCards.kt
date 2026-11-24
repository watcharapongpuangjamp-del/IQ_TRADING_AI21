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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.AlignmentState
import com.example.model.DataQualitySummary
import com.example.model.MultiTimeframeReport
import com.example.model.ReplaySessionState
import com.example.model.TradeDirection
import com.example.ui.theme.LocalTradingColors

@Composable
fun SimulatorReplayBarCard(
    replayState: ReplaySessionState?,
    isBusy: Boolean,
    onStartReplay: () -> Unit,
    onStepReplay: (Int) -> Unit,
    onResetReplay: () -> Unit,
    onExitReplay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val active = replayState?.active == true

    Surface(
        color = tradingColors.surfaceElevated,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (active) tradingColors.warning.copy(alpha = 0.65f) else tradingColors.cardBorder
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        imageVector = Icons.Rounded.History,
                        contentDescription = "Simulator Replay",
                        tint = if (active) tradingColors.warning else tradingColors.cyan
                    )
                    Column {
                        Text(
                            text = if (active) "Simulator Replay Active" else "Historical Simulator Replay",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (active) {
                                "Cursor ${replayState?.cursorIndex ?: 0}/${replayState?.totalBars ?: 0} • Zero Look-Ahead"
                            } else {
                                "จำลองกราฟย้อนหลังทีละแท่งโดยไม่เห็นอนาคต"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!active) {
                    OutlinedButton(
                        onClick = onStartReplay,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("start_replay_button")
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("เริ่ม Replay")
                    }
                } else {
                    OutlinedButton(
                        onClick = onExitReplay,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, tradingColors.bearish.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("exit_replay_button")
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = null, tint = tradingColors.bearish, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("กลับ Live", color = tradingColors.bearish)
                    }
                }
            }

            if (active && replayState != null) {
                val progress = if (replayState.totalBars > 0) {
                    (replayState.cursorIndex.toFloat() / replayState.totalBars.toFloat()).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    color = tradingColors.warning,
                    trackColor = tradingColors.cardBorder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onStepReplay(1) },
                        enabled = !isBusy && replayState.cursorIndex < replayState.totalBars,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tradingColors.cyan,
                            contentColor = tradingColors.surfaceCanvas
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("replay_step_1_button")
                    ) {
                        Text("+1 Bar", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onStepReplay(5) },
                        enabled = !isBusy && replayState.cursorIndex < replayState.totalBars,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tradingColors.warning,
                            contentColor = tradingColors.surfaceCanvas
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("replay_step_5_button")
                    ) {
                        Icon(Icons.Rounded.FastForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("+5 Bars", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onResetReplay,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("replay_reset_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Reset Replay", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun MultiTimeframeIntelligenceCard(
    mtfReport: MultiTimeframeReport?,
    dataQuality: DataQualitySummary?,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    if (mtfReport == null) return

    val (alignBg, alignFg) = when (mtfReport.alignmentState) {
        AlignmentState.ALIGNED_BULLISH -> tradingColors.bullishContainer to tradingColors.bullish
        AlignmentState.ALIGNED_BEARISH -> tradingColors.bearishContainer to tradingColors.bearish
        AlignmentState.CONFLICT -> tradingColors.warningContainer to tradingColors.warning
        AlignmentState.NEUTRAL -> tradingColors.cyanContainer to tradingColors.cyan
    }

    Surface(
        color = tradingColors.surfaceElevated,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, tradingColors.cardBorder),
        modifier = modifier.fillMaxWidth()
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
                    Icon(Icons.Rounded.Layers, contentDescription = null, tint = tradingColors.cyan)
                    Column {
                        Text(
                            text = "MULTI-TIMEFRAME MATRIX (1m + 5m/15m)",
                            style = MaterialTheme.typography.labelSmall,
                            color = tradingColors.cyan
                        )
                        Text(
                            text = mtfReport.alignmentState.labelTh,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Surface(
                    color = alignBg,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, alignFg.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "${mtfReport.alignmentScore}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = alignFg,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                mtfReport.timeframeSummaries.forEach { tf ->
                    val (dirColor, dirText) = when (tf.trendBias) {
                        TradeDirection.CALL -> tradingColors.bullish to "CALL ▲"
                        TradeDirection.PUT -> tradingColors.bearish to "PUT ▼"
                        TradeDirection.NO_TRADE -> tradingColors.warning to "WAIT"
                    }
                    Surface(
                        color = tradingColors.surfaceCanvas,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, tradingColors.cardBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = if (tf.timeframe == "1m") "1m Primary" else "${tf.timeframe} Confirm",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = dirText,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = dirColor
                            )
                            Text(
                                text = "RSI ${String.format("%.1f", tf.rsi)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (dataQuality != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Verified,
                        contentDescription = "Data Quality",
                        tint = if (dataQuality.valid) tradingColors.bullish else tradingColors.bearish,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Data Quality: ${dataQuality.reason} (${dataQuality.candleCount} bars, Gaps: ${dataQuality.gapCount})",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (dataQuality.valid) MaterialTheme.colorScheme.onSurfaceVariant else tradingColors.bearish
                    )
                }
            }
        }
    }
}
