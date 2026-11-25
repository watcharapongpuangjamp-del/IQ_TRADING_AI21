package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.CandlestickChart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.CandlestickChart
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.FirebaseBootstrap
import com.example.R
import com.example.data.local.TradingDatabase
import com.example.data.remote.BackendConnectorClient
import com.example.data.remote.TelegramNotifier
import com.example.data.repository.TradingCloudRepository
import com.example.model.RiskPolicy
import com.example.model.StrategyConfig
import com.example.security.SecureCredentialVault
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.authStateFlow
import com.example.ui.auth.signOutUser
import com.example.ui.notifications.TradingNotificationHelper
import com.example.ui.screens.PnLDashboardScreen
import com.example.ui.screens.SettingsDocsScreen
import com.example.ui.screens.StrategyRiskScreen
import com.example.ui.screens.TerminalScreen
import com.example.ui.theme.LocalTradingColors
import com.example.viewmodel.CloudUiState
import com.example.viewmodel.TradingViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private object NavRoutes {
    const val TERMINAL = "terminal"
    const val STRATEGY = "strategy"
    const val PNL = "pnl"
    const val SETTINGS_DOCS = "settings_docs"
}

private data class NavDestinationItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val NAV_ITEMS = listOf(
    NavDestinationItem(
        route = NavRoutes.TERMINAL,
        label = "Terminal",
        selectedIcon = Icons.Rounded.CandlestickChart,
        unselectedIcon = Icons.Outlined.CandlestickChart,
        testTag = "nav_terminal"
    ),
    NavDestinationItem(
        route = NavRoutes.STRATEGY,
        label = "Strategy",
        selectedIcon = Icons.Rounded.Tune,
        unselectedIcon = Icons.Outlined.Tune,
        testTag = "nav_strategy"
    ),
    NavDestinationItem(
        route = NavRoutes.PNL,
        label = "P&L",
        selectedIcon = Icons.Rounded.Analytics,
        unselectedIcon = Icons.Outlined.Analytics,
        testTag = "nav_pnl"
    ),
    NavDestinationItem(
        route = NavRoutes.SETTINGS_DOCS,
        label = "Connect",
        selectedIcon = Icons.AutoMirrored.Rounded.MenuBook,
        unselectedIcon = Icons.AutoMirrored.Outlined.MenuBook,
        testTag = "nav_settings_docs"
    )
)

@Composable
fun AppNavigation(auth: FirebaseAuth? = null) {
    val context = LocalContext.current
    var initAttempt by remember { mutableIntStateOf(0) }
    val resolvedAuth = remember(auth, context, initAttempt) {
        auth ?: FirebaseBootstrap.getAuthOrNull(context)
    }

    if (resolvedAuth == null) {
        FirebaseConfigMissingScreen(
            onRetry = { initAttempt++ }
        )
        return
    }

    val currentUser by resolvedAuth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = resolvedAuth.currentUser)
    val user = currentUser

    if (user == null) {
        AuthScreen(onAuthSuccess = { /* authStateFlow updates automatically */ })
    } else {
        key(user.uid) {
            AuthenticatedTradingShell(
                currentUserId = user.uid,
                displayName = user.displayName ?: "Trader",
                email = user.email
            )
        }
    }
}

@Composable
private fun FirebaseConfigMissingScreen(onRetry: () -> Unit) {
    val tradingColors = LocalTradingColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tradingColors.surfaceCanvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
            .testTag("firebase_config_missing_screen"),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = tradingColors.surfaceElevated,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, tradingColors.warning.copy(alpha = 0.5f)),
            modifier = Modifier.widthIn(max = 520.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = "Firebase configuration missing",
                        tint = tradingColors.warning,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "ยังไม่ได้ตั้งค่า Firebase ในไฟล์ APK",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "ไม่พบข้อมูลทรัพยากรจาก app/google-services.json ในบิลด์นี้ (เช่น บิลด์ผ่าน GitHub Actions โดยยังไม่ได้เพิ่ม Secret ชื่อ GOOGLE_SERVICES_JSON) จึงไม่สามารถเริ่มต้น FirebaseApp ได้",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "วิธีแก้ไข:\n1) ไปที่ GitHub Repository → Settings → Secrets and variables → Actions\n2) เพิ่ม Secret ชื่อ GOOGLE_SERVICES_JSON โดยใส่เนื้อหาจากไฟล์ app/google-services.json\n3) สั่งรัน Workflow Build APK ใหม่อีกครั้ง แล้วติดตั้ง APK ล่าสุด",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tradingColors.bullish,
                        contentColor = tradingColors.surfaceCanvas
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("retry_firebase_init_button")
                ) {
                    Text(
                        text = "ลองตรวจสอบการตั้งค่าอีกครั้ง",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthenticatedTradingShell(
    currentUserId: String,
    displayName: String,
    email: String?
) {
    val viewModel: TradingViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                TradingViewModel(
                    cloudRepository = TradingCloudRepository(db),
                    tradingDao = TradingDatabase.getInstance(app).tradingDao(),
                    credentialVault = SecureCredentialVault(app),
                    telegramNotifier = TelegramNotifier(),
                    backendClient = BackendConnectorClient(),
                    notificationHelper = TradingNotificationHelper(app),
                    currentUserId = currentUserId,
                    userDisplayName = displayName,
                    userEmail = email
                )
            }
        }
    )

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val tradingColors = LocalTradingColors.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val terminalState by viewModel.terminalState.collectAsStateWithLifecycle()
    val profileState by viewModel.profileState.collectAsStateWithLifecycle()
    val strategiesState by viewModel.strategiesState.collectAsStateWithLifecycle()
    val riskPolicyState by viewModel.riskPolicyState.collectAsStateWithLifecycle()
    val tradesState by viewModel.tradesState.collectAsStateWithLifecycle()
    val dailySummariesState by viewModel.dailySummariesState.collectAsStateWithLifecycle()
    val integrationSettings by viewModel.integrationSettings.collectAsStateWithLifecycle()
    val backendHealth by viewModel.backendHealth.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val isSyncingCloud by viewModel.isSyncingCloud.collectAsStateWithLifecycle()
    val mlModels by viewModel.mlModels.collectAsStateWithLifecycle()
    val activeMlModel by viewModel.activeMlModel.collectAsStateWithLifecycle()
    val isMlBusy by viewModel.isMlBusy.collectAsStateWithLifecycle()
    val backendOrders by viewModel.backendOrders.collectAsStateWithLifecycle()
    val selectedOrderEvents by viewModel.selectedOrderEvents.collectAsStateWithLifecycle()

    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    val userProfile = (profileState as? CloudUiState.Success)?.data
    val strategies = (strategiesState as? CloudUiState.Success)?.data.orEmpty()
    val activeStrategy = strategies.firstOrNull { it.active }
        ?: strategies.firstOrNull()
        ?: StrategyConfig(userId = currentUserId)
    val riskPolicy = (riskPolicyState as? CloudUiState.Success)?.data
        ?: RiskPolicy(userId = currentUserId)
    val trades = (tradesState as? CloudUiState.Success)?.data.orEmpty()
    val dailySummaries = (dailySummariesState as? CloudUiState.Success)?.data.orEmpty()
    val todayDateKey = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }

    val cloudError = listOf(profileState, strategiesState, riskPolicyState, tradesState)
        .filterIsInstance<CloudUiState.Error>()
        .firstOrNull()?.message

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: NavRoutes.TERMINAL

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(tradingColors.surfaceCanvas)
    ) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = tradingColors.surfaceCanvas,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        NAV_ITEMS.forEach { item ->
                            val selected = currentRoute == item.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = tradingColors.cyan,
                                    selectedTextColor = tradingColors.cyan,
                                    indicatorColor = tradingColors.cyanContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        NAV_ITEMS.forEach { item ->
                            val selected = currentRoute == item.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = tradingColors.cyan,
                                    selectedTextColor = tradingColors.cyan,
                                    indicatorColor = tradingColors.cyanContainer
                                ),
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = NavRoutes.TERMINAL,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 920.dp)
                            .align(Alignment.TopCenter)
                    ) {
                        composable(NavRoutes.TERMINAL) {
                            TerminalScreen(
                                terminalState = terminalState,
                                userProfile = userProfile,
                                activeStrategy = activeStrategy,
                                riskPolicy = riskPolicy,
                                backendConnected = backendHealth.connected,
                                onSelectInstrument = viewModel::selectInstrument,
                                onSelectTimeframe = viewModel::selectTimeframe,
                                onToggleAutoTrade = viewModel::toggleAutoTrade,
                                onManualTrade = viewModel::executeManualOrder,
                                onEmergencyStop = viewModel::triggerEmergencyStop,
                                onResetEmergencyStop = viewModel::resetEmergencyStop,
                                onStartReplay = viewModel::startReplayMode,
                                onStepReplay = viewModel::stepReplay,
                                onResetReplay = viewModel::resetReplay,
                                onExitReplay = viewModel::exitReplayMode,
                                onReconcileOrders = { viewModel.reconcileBackendOrders() },
                                onFlushSyncQueue = { viewModel.flushOfflineSyncQueue(forceRetryFailed = true, notifyUser = true) }
                            )
                        }
                        composable(NavRoutes.STRATEGY) {
                            StrategyRiskScreen(
                                strategy = activeStrategy,
                                riskPolicy = riskPolicy,
                                instrument = terminalState.selectedInstrument,
                                backendConnected = backendHealth.connected,
                                mlModels = mlModels,
                                activeMlModel = activeMlModel,
                                isMlBusy = isMlBusy,
                                onSaveStrategy = viewModel::saveStrategyConfig,
                                onSaveRiskPolicy = viewModel::saveRiskPolicyConfig,
                                onRefreshMlModels = viewModel::refreshMlModels,
                                onTrainMlModel = viewModel::trainMlModel,
                                onPromoteMlModel = viewModel::promoteMlModel,
                                strategyReport = terminalState.strategyReport,
                                isStrategyBusy = terminalState.isStrategyBusy,
                                onRefreshStrategies = viewModel::refreshStrategyModules,
                                onSelectStrategyModule = viewModel::selectStrategyModule
                            )
                        }
                        composable(NavRoutes.PNL) {
                            PnLDashboardScreen(
                                dailySummaries = dailySummaries,
                                trades = trades,
                                todayDateKey = todayDateKey,
                                backendOrders = backendOrders,
                                selectedOrderEvents = selectedOrderEvents,
                                isReconciling = terminalState.isReconciling,
                                onReconcileOrders = viewModel::reconcileBackendOrders,
                                onSelectBackendOrder = viewModel::loadOrderLifecycleEvents,
                                onClearOrderEvents = viewModel::clearSelectedOrderEvents
                            )
                        }
                        composable(NavRoutes.SETTINGS_DOCS) {
                            SettingsDocsScreen(
                                userProfile = userProfile,
                                settings = integrationSettings,
                                backendHealth = backendHealth,
                                isSyncingCloud = isSyncingCloud,
                                onSwitchMode = viewModel::switchExecutionMode,
                                onResetBalance = viewModel::resetSimulationBalance,
                                onSaveIntegrations = viewModel::saveIntegrationsAndTestBackend,
                                onTestBackend = viewModel::testBackendConnection,
                                onSendTelegramTest = viewModel::sendTelegramTest,
                                onSendLocalNotificationTest = viewModel::sendLocalNotificationTest,
                                onSyncCloudNow = viewModel::syncCloudBackupNow,
                                onSignOut = {
                                    signOutUser(credentialManager, scope)
                                }
                            )
                        }
                    }

                    if (!cloudError.isNullOrBlank()) {
                        Surface(
                            color = tradingColors.bearishContainer,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "Cloud Sync: $cloudError",
                                style = MaterialTheme.typography.bodySmall,
                                color = tradingColors.bearish,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
