package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.example.model.MlModelSummary
import com.example.model.RiskPolicy
import com.example.model.StrategyConfig
import com.example.model.StrategyModuleSummary
import com.example.model.StrategySelectionReport
import com.example.ui.components.MlGovernancePanel
import com.example.ui.theme.LocalTradingColors
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StrategyRiskScreen(
    strategy: StrategyConfig,
    riskPolicy: RiskPolicy,
    instrument: String,
    backendConnected: Boolean,
    mlModels: List<MlModelSummary>,
    activeMlModel: MlModelSummary?,
    isMlBusy: Boolean,
    onSaveStrategy: (StrategyConfig) -> Unit,
    onSaveRiskPolicy: (RiskPolicy) -> Unit,
    onRefreshMlModels: () -> Unit,
    onTrainMlModel: (String) -> Unit,
    onPromoteMlModel: (String) -> Unit,
    strategyReport: StrategySelectionReport? = null,
    isStrategyBusy: Boolean = false,
    onRefreshStrategies: () -> Unit = {},
    onSelectStrategyModule: (strategyId: String?, autoSelect: Boolean) -> Unit = { _, _ -> },
    onCheckMlDrift: (String) -> Unit = {},
    onRollbackMlModel: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    var selectedTab by remember { mutableIntStateOf(0) }

    // Strategy editable state synced from local SQLite
    var strategyName by remember(strategy.id, strategy.updatedAtMillis) { mutableStateOf(strategy.name) }
    var tradeAmount by remember(strategy.id, strategy.updatedAtMillis) { mutableDoubleStateOf(strategy.tradeAmount) }
    var rsiPeriod by remember(strategy.id, strategy.updatedAtMillis) { mutableIntStateOf(strategy.rsiPeriod) }
    var rsiOversold by remember(strategy.id, strategy.updatedAtMillis) { mutableIntStateOf(strategy.rsiOversold) }
    var rsiOverbought by remember(strategy.id, strategy.updatedAtMillis) { mutableIntStateOf(strategy.rsiOverbought) }
    var emaFast by remember(strategy.id, strategy.updatedAtMillis) { mutableIntStateOf(strategy.emaFast) }
    var emaSlow by remember(strategy.id, strategy.updatedAtMillis) { mutableIntStateOf(strategy.emaSlow) }
    var bbPeriod by remember(strategy.id, strategy.updatedAtMillis) { mutableIntStateOf(strategy.bbPeriod) }
    var bbStdDev by remember(strategy.id, strategy.updatedAtMillis) { mutableDoubleStateOf(strategy.bbStdDev) }
    var minConfidence by remember(strategy.id, strategy.updatedAtMillis) { mutableIntStateOf(strategy.minConfidence) }

    // Risk policy editable state
    var maxTradeAmount by remember(riskPolicy.updatedAtMillis) { mutableDoubleStateOf(riskPolicy.maxTradeAmount) }
    var maxDailyLoss by remember(riskPolicy.updatedAtMillis) { mutableDoubleStateOf(riskPolicy.maxDailyLoss) }
    var maxConsecutiveLosses by remember(riskPolicy.updatedAtMillis) { mutableIntStateOf(riskPolicy.maxConsecutiveLosses) }
    var maxDailyTrades by remember(riskPolicy.updatedAtMillis) { mutableIntStateOf(riskPolicy.maxDailyTrades) }
    var maxPortfolioExposure by remember(riskPolicy.updatedAtMillis) { mutableDoubleStateOf(riskPolicy.maxPortfolioExposure) }
    var cooldownMinutes by remember(riskPolicy.updatedAtMillis) { mutableIntStateOf(riskPolicy.cooldownMinutes) }

    LaunchedEffect(strategy) {
        strategyName = strategy.name
        tradeAmount = strategy.tradeAmount
        rsiPeriod = strategy.rsiPeriod
        rsiOversold = strategy.rsiOversold
        rsiOverbought = strategy.rsiOverbought
        emaFast = strategy.emaFast
        emaSlow = strategy.emaSlow
        bbPeriod = strategy.bbPeriod
        bbStdDev = strategy.bbStdDev
        minConfidence = strategy.minConfidence
    }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = tradingColors.cyan
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("กลยุทธ์", fontWeight = FontWeight.SemiBold) },
                modifier = Modifier.testTag("tab_strategy_studio")
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Risk Gate", fontWeight = FontWeight.SemiBold) },
                modifier = Modifier.testTag("tab_risk_guardrails")
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("ML Models", fontWeight = FontWeight.SemiBold) },
                modifier = Modifier.testTag("tab_ml_models")
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (selectedTab == 0) {
                item {
                    AiStrategySelectorSection(
                        report = strategyReport,
                        isBusy = isStrategyBusy,
                        onRefresh = onRefreshStrategies,
                        onSelectModule = onSelectStrategyModule
                    )
                }

                item {
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
                            Text(
                                text = "พรีเซ็ตกลยุทธ์ยอดนิยม",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        strategyName = "Scalp EMA 9/21"
                                        emaFast = 9; emaSlow = 21; rsiPeriod = 14
                                        rsiOversold = 30; rsiOverbought = 70; minConfidence = 64
                                    },
                                    label = { Text("Scalp EMA", style = MaterialTheme.typography.labelSmall) },
                                    colors = FilterChipDefaults.filterChipColors(),
                                    modifier = Modifier.testTag("preset_scalp_ema")
                                )
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        strategyName = "Bollinger Reversion"
                                        bbPeriod = 20; bbStdDev = 2.2
                                        rsiOversold = 25; rsiOverbought = 75; minConfidence = 70
                                    },
                                    label = { Text("BB Reversion", style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.testTag("preset_bb_reversion")
                                )
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        strategyName = "Conservative Trend"
                                        emaFast = 12; emaSlow = 50; minConfidence = 78
                                    },
                                    label = { Text("Conservative", style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.testTag("preset_conservative")
                                )
                            }

                            OutlinedTextField(
                                value = strategyName,
                                onValueChange = { if (it.length <= 80) strategyName = it },
                                label = { Text("ชื่อกลยุทธ์") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("strategy_name_input")
                            )

                            LabeledSlider(
                                title = "ขนาดเงินลงทุนต่อไม้",
                                valueText = "$${tradeAmount.roundToInt()}",
                                value = tradeAmount.toFloat(),
                                range = 5f..maxTradeAmount.toFloat().coerceAtLeast(10f),
                                onValueChange = { tradeAmount = it.roundToInt().toDouble() }
                            )

                            LabeledSlider(
                                title = "เกณฑ์ความมั่นใจขั้นต่ำ (Min Confidence)",
                                valueText = "$minConfidence%",
                                value = minConfidence.toFloat(),
                                range = 50f..95f,
                                onValueChange = { minConfidence = it.roundToInt() }
                            )
                        }
                    }
                }

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
                                text = "พารามิเตอร์กราฟเทคนิค (RSI / EMA / BB)",
                                style = MaterialTheme.typography.titleMedium,
                                color = tradingColors.cyan
                            )

                            LabeledSlider(
                                title = "RSI Period",
                                valueText = "$rsiPeriod",
                                value = rsiPeriod.toFloat(),
                                range = 5f..30f,
                                onValueChange = { rsiPeriod = it.roundToInt() }
                            )
                            LabeledSlider(
                                title = "RSI Oversold (โซนเข้า CALL)",
                                valueText = "$rsiOversold",
                                value = rsiOversold.toFloat(),
                                range = 15f..42f,
                                onValueChange = { rsiOversold = it.roundToInt() }
                            )
                            LabeledSlider(
                                title = "RSI Overbought (โซนเข้า PUT)",
                                valueText = "$rsiOverbought",
                                value = rsiOverbought.toFloat(),
                                range = 58f..88f,
                                onValueChange = { rsiOverbought = it.roundToInt() }
                            )
                            LabeledSlider(
                                title = "EMA Fast / Slow",
                                valueText = "$emaFast / $emaSlow",
                                value = emaFast.toFloat(),
                                range = 3f..25f,
                                onValueChange = {
                                    emaFast = it.roundToInt()
                                    if (emaSlow <= emaFast) emaSlow = emaFast + 8
                                }
                            )
                            LabeledSlider(
                                title = "EMA Slow",
                                valueText = "$emaSlow",
                                value = emaSlow.toFloat(),
                                range = (emaFast + 4).toFloat().coerceAtMost(90f)..100f,
                                onValueChange = { emaSlow = it.roundToInt() }
                            )
                            LabeledSlider(
                                title = "Bollinger Bands StdDev",
                                valueText = String.format("%.1f", bbStdDev),
                                value = bbStdDev.toFloat(),
                                range = 1.0f..3.5f,
                                onValueChange = { bbStdDev = ((it * 10).roundToInt() / 10.0) }
                            )

                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = {
                                    onSaveStrategy(
                                        strategy.copy(
                                            name = strategyName.ifBlank { "Custom Strategy" },
                                            tradeAmount = tradeAmount.coerceIn(1.0, maxTradeAmount),
                                            rsiPeriod = rsiPeriod,
                                            rsiOversold = rsiOversold,
                                            rsiOverbought = rsiOverbought,
                                            emaFast = emaFast,
                                            emaSlow = emaSlow.coerceAtLeast(emaFast + 1),
                                            bbPeriod = bbPeriod,
                                            bbStdDev = bbStdDev,
                                            minConfidence = minConfidence
                                        )
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = tradingColors.bullish,
                                    contentColor = tradingColors.surfaceCanvas
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("save_strategy_button")
                            ) {
                                Text("บันทึกกลยุทธ์และซิงค์ขึ้น Cloud", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else if (selectedTab == 1) {
                item {
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
                            Text(
                                text = "การตั้งค่าจุดตัดขาดทุนอัตโนมัติ (Risk Guardrails)",
                                style = MaterialTheme.typography.titleMedium,
                                color = tradingColors.warning
                            )
                            Text(
                                text = "ระบบ Risk Engine ทำงานเป็นอิสระ ไม่มี AI หรือกลยุทธ์ใดสามารถสั่งข้ามลิมิตเหล่านี้ได้",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            LabeledSlider(
                                title = "ขาดทุนสูงสุดต่อวัน (Daily Stop-Loss)",
                                valueText = "$${maxDailyLoss.roundToInt()}",
                                value = maxDailyLoss.toFloat(),
                                range = 25f..2000f,
                                onValueChange = { maxDailyLoss = it.roundToInt().toDouble() }
                            )
                            LabeledSlider(
                                title = "จำกัดจำนวนแพ้ติดต่อกันสูงสุด",
                                valueText = "$maxConsecutiveLosses ไม้",
                                value = maxConsecutiveLosses.toFloat(),
                                range = 1f..10f,
                                onValueChange = { maxConsecutiveLosses = it.roundToInt() }
                            )
                            LabeledSlider(
                                title = "วงเงินสูงสุดต่อ 1 ไม้",
                                valueText = "$${maxTradeAmount.roundToInt()}",
                                value = maxTradeAmount.toFloat(),
                                range = 10f..1000f,
                                onValueChange = { maxTradeAmount = it.roundToInt().toDouble() }
                            )
                            LabeledSlider(
                                title = "จำนวนออเดอร์สูงสุดต่อวัน",
                                valueText = "$maxDailyTrades ไม้",
                                value = maxDailyTrades.toFloat(),
                                range = 5f..150f,
                                onValueChange = { maxDailyTrades = it.roundToInt() }
                            )
                            LabeledSlider(
                                title = "เพดานความเสี่ยงพอร์ตรวม (Max Exposure)",
                                valueText = "$${maxPortfolioExposure.roundToInt()}",
                                value = maxPortfolioExposure.toFloat(),
                                range = 50f..5000f,
                                onValueChange = { maxPortfolioExposure = it.roundToInt().toDouble() }
                            )
                            LabeledSlider(
                                title = "ระยะพัก Cooldown หลังขาดทุน",
                                valueText = "$cooldownMinutes นาที",
                                value = cooldownMinutes.toFloat(),
                                range = 0f..120f,
                                onValueChange = { cooldownMinutes = it.roundToInt() }
                            )

                            Button(
                                onClick = {
                                    onSaveRiskPolicy(
                                        riskPolicy.copy(
                                            maxTradeAmount = maxTradeAmount,
                                            maxDailyLoss = maxDailyLoss,
                                            maxConsecutiveLosses = maxConsecutiveLosses,
                                            maxDailyTrades = maxDailyTrades,
                                            maxPortfolioExposure = maxPortfolioExposure,
                                            cooldownMinutes = cooldownMinutes
                                        )
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = tradingColors.warning,
                                    contentColor = tradingColors.surfaceCanvas
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("save_risk_policy_button")
                            ) {
                                Text("บันทึกกฎจุดตัดขาดทุนและซิงค์ Cloud", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                item {
                    MlGovernancePanel(
                        instrument = instrument,
                        backendConnected = backendConnected,
                        mlModels = mlModels,
                        activeModel = activeMlModel,
                        isBusy = isMlBusy,
                        onRefreshModels = onRefreshMlModels,
                        onTrainModel = onTrainMlModel,
                        onPromoteModel = onPromoteMlModel,
                        onCheckDrift = onCheckMlDrift,
                        onRollbackModel = onRollbackMlModel
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    val tradingColors = LocalTradingColors.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = tradingColors.cyan)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = tradingColors.cyan,
                activeTrackColor = tradingColors.cyan,
                inactiveTrackColor = tradingColors.cardBorder
            )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiStrategySelectorSection(
    report: StrategySelectionReport?,
    isBusy: Boolean,
    onRefresh: () -> Unit,
    onSelectModule: (strategyId: String?, autoSelect: Boolean) -> Unit
) {
    val tradingColors = LocalTradingColors.current
    Surface(
        color = tradingColors.surfaceElevated,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, tradingColors.cyan.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_strategy_selector_card")
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
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "AI Strategy Selector (8 โมดูล)",
                        style = MaterialTheme.typography.titleMedium,
                        color = tradingColors.cyan,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (report != null) {
                            "สภาวะตลาด: ${report.currentRegime.labelTh} • กำลังใช้: ${report.selectedStrategyName}"
                        } else {
                            "กำลังเตรียมการประเมินกลยุทธ์ 8 โมดูลตาม Market Regime..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("refresh_strategies_button")
                ) {
                    Text(
                        text = if (isBusy) "กำลังโหลด..." else "รีเฟรช 8 กลยุทธ์",
                        style = MaterialTheme.typography.labelMedium,
                        color = tradingColors.cyan
                    )
                }
            }

            if (report != null) {
                Surface(
                    color = tradingColors.surfaceCanvas,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, tradingColors.cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "เลือกกลยุทธ์อัตโนมัติตาม Market Regime",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = report.selectionReason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = report.autoSelected,
                            onCheckedChange = { enabled ->
                                onSelectModule(if (enabled) null else report.selectedStrategyId, enabled)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = tradingColors.surfaceCanvas,
                                checkedTrackColor = tradingColors.bullish
                            ),
                            modifier = Modifier.testTag("auto_strategy_toggle")
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    report.strategies.forEach { mod ->
                        StrategyModuleCard(
                            module = mod,
                            isBusy = isBusy,
                            onSelect = { onSelectModule(mod.strategyId, false) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StrategyModuleCard(
    module: StrategyModuleSummary,
    isBusy: Boolean,
    onSelect: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    Surface(
        color = tradingColors.surfaceCanvas,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (module.selected) 1.5.dp else 1.dp,
            color = if (module.selected) tradingColors.bullish else tradingColors.cardBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("strategy_module_card_${module.strategyId}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = module.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (module.selected) tradingColors.bullish else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = module.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = if (module.selected) tradingColors.bullishContainer else tradingColors.cyanContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Score ${String.format("%.1f", module.selectionScore)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (module.selected) tradingColors.bullish else tradingColors.cyan,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                module.targetRegimes.take(4).forEach { regime ->
                    Surface(
                        color = tradingColors.surfaceElevated,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, tradingColors.cardBorder)
                    ) {
                        Text(
                            text = regime,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Win ${String.format("%.1f%%", module.winRate)} • PF ${String.format("%.2f", module.profitFactor)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = tradingColors.bullish
                )
                Text(
                    text = "DD ${String.format("%.1f%%", module.maxDrawdown)} • EV ${String.format("%+.2f", module.expectedValue)} (n=${module.sampleCount})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "เงื่อนไขเข้า: ${module.entryConditions}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onSelect,
                enabled = !isBusy && !module.selected,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (module.selected) tradingColors.bullishContainer else tradingColors.cyan,
                    contentColor = if (module.selected) tradingColors.bullish else tradingColors.surfaceCanvas,
                    disabledContainerColor = tradingColors.bullishContainer,
                    disabledContentColor = tradingColors.bullish
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("select_strategy_${module.strategyId}")
            ) {
                Text(
                    text = if (module.selected) "กำลังใช้งานกลยุทธ์นี้" else "ใช้งานกลยุทธ์นี้",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

