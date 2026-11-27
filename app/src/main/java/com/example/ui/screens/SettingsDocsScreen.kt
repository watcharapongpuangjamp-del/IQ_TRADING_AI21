package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.model.BackendHealthState
import com.example.model.BrokerDryRunReport
import com.example.model.ExecutionMode
import com.example.model.IntegrationSettings
import com.example.model.LiveReadinessReport
import com.example.model.LocalDatabaseStats
import com.example.model.RiskPolicy
import com.example.model.SystemDiagnosticsReport
import com.example.model.UserProfile
import com.example.ui.components.DiagnosticsLiveGatePanel
import com.example.ui.theme.LocalTradingColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsDocsScreen(
    userProfile: UserProfile?,
    databaseStats: LocalDatabaseStats,
    settings: IntegrationSettings,
    backendHealth: BackendHealthState,
    riskPolicy: RiskPolicy = RiskPolicy(),
    liveReadiness: LiveReadinessReport = LiveReadinessReport(),
    diagnosticsReport: SystemDiagnosticsReport? = null,
    isDiagnosticsBusy: Boolean = false,
    onRunDiagnostics: () -> Unit = {},
    dryRunReport: BrokerDryRunReport? = null,
    isDryRunBusy: Boolean = false,
    onRunBrokerDryRun: () -> Unit = {},
    onSwitchMode: (ExecutionMode, Boolean) -> Unit,
    onResetBalance: () -> Unit,
    onSaveIntegrations: (
        backendUrl: String,
        wsUrl: String,
        chatId: String,
        notifySignal: Boolean,
        notifyTrade: Boolean,
        notifyRisk: Boolean,
        notifyDaily: Boolean,
        enableLocalNotifications: Boolean,
        newBackendToken: String?,
        newTelegramToken: String?
    ) -> Unit,
    onTestBackend: () -> Unit,
    onSendTelegramTest: () -> Unit,
    onSendLocalNotificationTest: () -> Unit,
    onUpdateTraderName: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val context = LocalContext.current
    val currentMode = ExecutionMode.safeValueOf(userProfile?.executionMode)
    var selectedTab by remember { mutableIntStateOf(0) }
    var traderNameInput by remember(userProfile?.displayName) {
        mutableStateOf(userProfile?.displayName ?: "Local Trader")
    }

    var hasNotifPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPermission = granted
    }

    var backendUrl by remember(settings.backendBaseUrl) { mutableStateOf(settings.backendBaseUrl) }
    var wsUrl by remember(settings.backendWsUrl) { mutableStateOf(settings.backendWsUrl) }
    var backendTokenInput by remember { mutableStateOf("") }
    var telegramTokenInput by remember { mutableStateOf("") }
    var telegramChatId by remember(settings.telegramChatId) { mutableStateOf(settings.telegramChatId) }
    var notifySignal by remember(settings.notifyOnSignal) { mutableStateOf(settings.notifyOnSignal) }
    var notifyTrade by remember(settings.notifyOnTradeResult) { mutableStateOf(settings.notifyOnTradeResult) }
    var notifyRisk by remember(settings.notifyOnRiskStop) { mutableStateOf(settings.notifyOnRiskStop) }
    var notifyDaily by remember(settings.notifyDailySummary) { mutableStateOf(settings.notifyDailySummary) }
    var enableLocalNotif by remember(settings.enableLocalNotifications) { mutableStateOf(settings.enableLocalNotifications) }

    LaunchedEffect(settings) {
        if (backendUrl.isEmpty() && settings.backendBaseUrl.isNotEmpty()) backendUrl = settings.backendBaseUrl
        if (wsUrl.isEmpty() && settings.backendWsUrl.isNotEmpty()) wsUrl = settings.backendWsUrl
        if (telegramChatId.isEmpty() && settings.telegramChatId.isNotEmpty()) telegramChatId = settings.telegramChatId
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
                text = { Text("Connect & SQLite", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                modifier = Modifier.testTag("tab_connect_sqlite")
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Diagnostics & Live Gate", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                modifier = Modifier.testTag("tab_diagnostics_live")
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (selectedTab == 1) {
                item {
                    DiagnosticsLiveGatePanel(
                        liveReadiness = liveReadiness,
                        diagnosticsReport = diagnosticsReport,
                        isDiagnosticsBusy = isDiagnosticsBusy,
                        riskPolicy = riskPolicy,
                        backendHealth = backendHealth,
                        onRunDiagnostics = onRunDiagnostics,
                        onSwitchMode = onSwitchMode,
                        dryRunReport = dryRunReport,
                        isDryRunBusy = isDryRunBusy,
                        onRunBrokerDryRun = onRunBrokerDryRun
                    )
                }
            } else {
        // 1. Execution Mode Controller & FastAPI Hybrid Connector
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Router, contentDescription = null, tint = tradingColors.cyan)
                        Text(
                            text = "Hybrid FastAPI Backend & Execution Mode",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExecutionMode.entries.forEach { mode ->
                            val isLiveLocked = mode == ExecutionMode.LIVE
                            FilterChip(
                                selected = currentMode == mode,
                                enabled = !isLiveLocked,
                                onClick = {
                                    if (mode == ExecutionMode.LIVE) {
                                        selectedTab = 1
                                    } else {
                                        onSwitchMode(mode, false)
                                    }
                                },
                                label = {
                                    Text(
                                        text = if (isLiveLocked) "REAL (LOCKED)" else mode.label,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = if (mode == ExecutionMode.LIVE) tradingColors.warningContainer else tradingColors.cyanContainer,
                                    selectedLabelColor = if (mode == ExecutionMode.LIVE) tradingColors.warning else tradingColors.cyan
                                ),
                                modifier = Modifier.testTag("mode_${mode.name.lowercase()}")
                            )
                        }
                    }

                    Text(
                        text = "สถานะ Connector: ${backendHealth.statusMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (backendHealth.connected) tradingColors.bullish else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = backendUrl,
                        onValueChange = { backendUrl = it },
                        label = { Text("FastAPI REST Base URL (เช่น https://api.yourdomain.com)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("backend_url_input")
                    )

                    OutlinedTextField(
                        value = wsUrl,
                        onValueChange = { wsUrl = it },
                        label = { Text("FastAPI WebSocket URL (wss://...)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("backend_ws_input")
                    )

                    OutlinedTextField(
                        value = backendTokenInput,
                        onValueChange = { backendTokenInput = it },
                        label = {
                            Text(
                                if (settings.hasBackendToken) "Backend Bearer Token (เข้ารหัสไว้แล้ว — เว้นว่างหากไม่เปลี่ยน)"
                                else "Backend Bearer Token (ไม่บังคับ)"
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("backend_token_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onTestBackend,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(48.dp).testTag("test_backend_button")
                        ) {
                            Text("ทดสอบ Backend")
                        }
                        OutlinedButton(
                            onClick = onResetBalance,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(48.dp).testTag("reset_sim_balance_button")
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("รีเซ็ต $10K")
                        }
                    }
                }
            }
        }

        // 2. Telegram Notifications Card
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.NotificationsActive, contentDescription = null, tint = tradingColors.bullish)
                        Text(
                            text = "การแจ้งเตือนผ่าน Telegram Bot (AES-256 Encrypted)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    OutlinedTextField(
                        value = telegramTokenInput,
                        onValueChange = { telegramTokenInput = it },
                        label = {
                            Text(
                                if (settings.hasTelegramToken) "Telegram Bot Token (บันทึกแล้ว — กรอกใหม่เพื่อแทนที่)"
                                else "Telegram Bot Token (จาก @BotFather)"
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("telegram_token_input")
                    )

                    OutlinedTextField(
                        value = telegramChatId,
                        onValueChange = { telegramChatId = it },
                        label = { Text("Telegram Chat ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("telegram_chat_id_input")
                    )

                    CheckOptionRow("แจ้งเตือนบนอุปกรณ์ Android (Local Notification)", enableLocalNotif) { enableLocalNotif = it }
                    CheckOptionRow("แจ้งเตือนเมื่อมีสัญญาณ AI (CALL/PUT)", notifySignal) { notifySignal = it }
                    CheckOptionRow("แจ้งเตือนผลปิดออเดอร์ (WON/LOST)", notifyTrade) { notifyTrade = it }
                    CheckOptionRow("แจ้งเตือนเมื่อ Risk Guard / Emergency Stop ทำงาน", notifyRisk) { notifyRisk = it }
                    CheckOptionRow("แจ้งเตือนสรุป P&L รายวัน", notifyDaily) { notifyDaily = it }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPermission) {
                        OutlinedButton(
                            onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("request_notif_permission_button")
                        ) {
                            Text("อนุญาตสิทธิ์แจ้งเตือนบน Android 13+")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                onSaveIntegrations(
                                    backendUrl,
                                    wsUrl,
                                    telegramChatId,
                                    notifySignal,
                                    notifyTrade,
                                    notifyRisk,
                                    notifyDaily,
                                    enableLocalNotif,
                                    backendTokenInput.takeIf { it.isNotBlank() },
                                    telegramTokenInput.takeIf { it.isNotBlank() }
                                )
                                backendTokenInput = ""
                                telegramTokenInput = ""
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tradingColors.bullish,
                                contentColor = tradingColors.surfaceCanvas
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(48.dp).testTag("save_integrations_button")
                        ) {
                            Text("เข้ารหัสและบันทึก", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onSendTelegramTest,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(48.dp).testTag("test_telegram_button")
                        ) {
                            Text("ทดสอบ Telegram")
                        }
                    }

                    OutlinedButton(
                        onClick = onSendLocalNotificationTest,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("test_local_notification_button")
                    ) {
                        Text("ทดสอบแจ้งเตือนบนมือถือ (Local Notification)")
                    }
                }
            }
        }

        // 3. Local SQLite Storage & Trader Profile
        item {
            val lastSavedStr = databaseStats.lastSavedAtMillis?.let {
                SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.getDefault()).format(Date(it))
            } ?: "บันทึกลง SQLite บนเครื่องอัตโนมัติ"

            Surface(
                color = tradingColors.surfaceElevated,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, tradingColors.cardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("local_sqlite_storage_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Storage, contentDescription = "Local SQLite Database", tint = tradingColors.cyan)
                        Text(
                            text = "ฐานข้อมูล SQL on Local (Room SQLite)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "ไฟล์ฐานข้อมูล: ${databaseStats.databaseName} (Local-First ไม่มี Cloud Sync)",
                        style = MaterialTheme.typography.bodySmall,
                        color = tradingColors.cyan
                    )
                    Text(
                        text = "ข้อมูลบนเครื่อง: ประวัติเทรด ${databaseStats.totalTrades} ไม้ • แท่งเทียน ${databaseStats.totalCachedCandles} แท่ง • กลยุทธ์ ${databaseStats.totalStrategies} ชุด • สรุปรายวัน ${databaseStats.totalDailySummaries} วัน",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "บันทึกล่าสุด: $lastSavedStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = traderNameInput,
                        onValueChange = { traderNameInput = it },
                        label = { Text("ชื่อโปรไฟล์ Trader บนเครื่อง (Local SQLite)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("local_trader_name_input")
                    )

                    Button(
                        onClick = { onUpdateTraderName(traderNameInput) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tradingColors.cyan,
                            contentColor = tradingColors.surfaceCanvas
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_local_profile_button")
                    ) {
                        Text("บันทึกโปรไฟล์ลง SQLite", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 4. Comprehensive In-App Documentation Center
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null, tint = tradingColors.warning)
                        Text(
                            text = "คู่มือการใช้งานและสถาปัตยกรรมระบบ",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    DocAccordionItem(
                        title = "1. สถาปัตยกรรม Hybrid และความปลอดภัยของ Broker",
                        body = "แอปออกแบบด้วย Hybrid Architecture: โหมด SIMULATION ทำงานได้ทันทีบนมือถือ ส่วนการเชื่อมต่อโบรกเกอร์ IQ Option จริงต้องทำงานผ่าน Python FastAPI Backend ภายนอกที่แยก Broker Connector Interface อิสระ แอป Android จะไม่เก็บรหัสผ่านโบรกเกอร์เด็ดขาด และโหมด LIVE จะถูกล็อกจนกว่า Backend จะผ่านการตรวจสอบ"
                    )
                    DocAccordionItem(
                        title = "2. การอ่านสัญญาณ AI และตัวชี้วัดเทคนิค",
                        body = "ระบบคำนวณ RSI (Wilder), MACD (12/26/9), EMA Fast/Slow, Bollinger Bands, ADX, Stochastic และ VWAP เพื่อจำแนก Market Regime พร้อมแยกแสดง Model Confidence ออกจาก Calibrated Win Probability และแนะนำ NO TRADE เมื่อสัญญาณขัดแย้งหรือตลาดผันผวนเกินเกณฑ์"
                    )
                    DocAccordionItem(
                        title = "3. ระบบ Risk Management และจุดตัดขาดทุนอัตโนมัติ",
                        body = "Risk Engine ตรวจสอบ 7 เงื่อนไขก่อนส่งทุกคำสั่ง ได้แก่ Emergency Stop, วงเงินสูงสุดต่อไม้, จุดตัดขาดทุนรายวัน (Max Daily Loss), จำนวนแพ้ติดต่อกันสูงสุด, จำนวนไม้ต่อวัน, เพดานความเสี่ยงพอร์ต และ Cooldown หลังขาดทุน"
                    )
                    DocAccordionItem(
                        title = "4. วิธีตั้งค่าแจ้งเตือน Telegram Bot",
                        body = "1) ค้นหา @BotFather ใน Telegram และสร้างบอทด้วยคำสั่ง /newbot เพื่อรับ Bot Token\n2) ส่งข้อความทักบอทของคุณ จากนั้นนำ Chat ID มาใส่ในหน้านี้\n3) Token จะถูกเข้ารหัสด้วย Android Keystore AES-256-GCM บนเครื่องของคุณเท่านั้น"
                    )
                    DocAccordionItem(
                        title = "5. ระบบจัดเก็บข้อมูล SQL on Local (Room SQLite)",
                        body = "ข้อมูลโปรไฟล์ กลยุทธ์ นโยบายความเสี่ยง ประวัติการเทรด สรุป P&L รายวัน และแคชแท่งเทียนถูกจัดเก็บโดยตรงในฐานข้อมูล Room SQLite บนอุปกรณ์ของคุณ (iq_trading_local_cache.db) โดยไม่ต้องพึ่งระบบ Cloud Sync หรือการเข้าสู่ระบบผ่านคลาวด์"
                    )
                }
            }
        }
            }
        }
    }
}

@Composable
private fun CheckOptionRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DocAccordionItem(
    title: String,
    body: String
) {
    val tradingColors = LocalTradingColors.current
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = tradingColors.surfaceCanvas,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, tradingColors.cardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = tradingColors.cyan,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
