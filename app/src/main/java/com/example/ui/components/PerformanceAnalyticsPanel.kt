package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.PerformanceAnalyticsReport
import com.example.model.PerformanceBreakdownRow
import com.example.ui.theme.LocalTradingColors
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PerformanceAnalyticsPanel(
    report: PerformanceAnalyticsReport,
    selectedModeFilter: String,
    onSelectModeFilter: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    var breakdownTab by remember { mutableIntStateOf(0) }
    val summary = report.activeSummary

    val breakdownRows: List<PerformanceBreakdownRow> = when (breakdownTab) {
        0 -> report.byStrategy
        1 -> report.byRegime
        2 -> report.byAsset
        else -> report.byTimeframe
    }

    Surface(
        color = tradingColors.surfaceElevated,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, tradingColors.cardBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("performance_analytics_panel")
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
                    imageVector = Icons.Rounded.PieChart,
                    contentDescription = "Performance Analytics",
                    tint = tradingColors.cyan
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Performance Analytics (แยก SIM / PRACTICE / LIVE)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "SIM ${report.simulationSummary.totalTrades} ไม้ | LIVE ${report.liveSummary.totalTrades} ไม้",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Mode Isolation Filter Chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "SIMULATION" to "SIMULATION",
                    "PRACTICE" to "PRACTICE",
                    "LIVE" to "LIVE",
                    "ALL" to "All (Labeled)"
                ).forEach { (key, label) ->
                    val selected = selectedModeFilter.equals(key, ignoreCase = true)
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectModeFilter(key) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = if (key == "LIVE") tradingColors.warningContainer else tradingColors.cyanContainer,
                            selectedLabelColor = if (key == "LIVE") tradingColors.warning else tradingColors.cyan
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("analytics_mode_${key.lowercase()}")
                    )
                }
            }

            if (selectedModeFilter.equals("ALL", ignoreCase = true)) {
                Surface(
                    color = tradingColors.warningContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "โหมดรวม (All Labeled): ใช้สำหรับภาพรวมเท่านั้น กรุณาตรวจสอบแท็บ SIMULATION และ LIVE แยกกันก่อนตัดสินใจจริง",
                        style = MaterialTheme.typography.bodySmall,
                        color = tradingColors.warning,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // KPI Metrics Grid (2 rows of 3)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnalyticsKpiCell(
                    label = "Net P&L (${summary.mode})",
                    value = "${if (summary.netPnl >= 0) "+" else "-"}$${String.format("%.2f", abs(summary.netPnl))}",
                    color = if (summary.netPnl >= 0) tradingColors.bullish else tradingColors.bearish,
                    modifier = Modifier.weight(1f)
                )
                AnalyticsKpiCell(
                    label = "Win Rate",
                    value = "${String.format("%.1f%%", summary.winRatePct)} (${summary.wins}W/${summary.draws}D/${summary.losses}L)",
                    color = tradingColors.cyan,
                    modifier = Modifier.weight(1f)
                )
                AnalyticsKpiCell(
                    label = "Profit Factor",
                    value = String.format("%.2f", summary.profitFactor),
                    color = if (summary.profitFactor >= 1.2) tradingColors.bullish else tradingColors.warning,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnalyticsKpiCell(
                    label = "Gross + / -",
                    value = "+$${String.format("%.0f", summary.grossProfit)} / -$${String.format("%.0f", summary.grossLoss)}",
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                AnalyticsKpiCell(
                    label = "Expectancy / DD",
                    value = "${String.format("%+.2f", summary.expectancy)} / $${String.format("%.0f", summary.maxDrawdown)}",
                    color = tradingColors.warning,
                    modifier = Modifier.weight(1f)
                )
                AnalyticsKpiCell(
                    label = "Win/Loss Streak",
                    value = "${summary.maxConsecutiveWins}W / ${summary.maxConsecutiveLosses}L",
                    color = tradingColors.cyan,
                    modifier = Modifier.weight(1f)
                )
            }

            // Breakdown Dimensional Tabs
            TabRow(
                selectedTabIndex = breakdownTab,
                containerColor = tradingColors.surfaceCanvas,
                contentColor = tradingColors.cyan
            ) {
                listOf("Strategy", "Regime", "Asset", "Timeframe").forEachIndexed { idx, title ->
                    Tab(
                        selected = breakdownTab == idx,
                        onClick = { breakdownTab = idx },
                        text = { Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                        modifier = Modifier.testTag("analytics_breakdown_tab_${title.lowercase()}")
                    )
                }
            }

            if (breakdownRows.isEmpty()) {
                Surface(
                    color = tradingColors.surfaceCanvas,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "ยังไม่มีออเดอร์ที่ปิดผลในโหมด ${summary.mode} สำหรับตารางแยกย่อยนี้",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    breakdownRows.forEachIndexed { idx, row ->
                        val pnlPositive = row.netPnl >= 0.0
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = row.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${row.totalTrades} ไม้ • Win ${String.format("%.1f%%", row.winRatePct)} • PF ${String.format("%.2f", row.profitFactor)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${if (pnlPositive) "+" else "-"}$${String.format("%.2f", abs(row.netPnl))}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (pnlPositive) tradingColors.bullish else tradingColors.bearish
                            )
                        }
                        if (idx < breakdownRows.lastIndex) {
                            HorizontalDivider(color = tradingColors.cardBorder)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsKpiCell(
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
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}
