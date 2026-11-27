package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.CandlestickChart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.CandlestickChart
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.preferencesDataStore
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
import com.example.data.local.TradingDatabase
import com.example.data.remote.BackendConnectorClient
import com.example.data.remote.TelegramNotifier
import com.example.data.repository.LocalTradingRepository
import com.example.model.RiskPolicy
import com.example.model.StrategyConfig
import com.example.security.SecureCredentialVault
import com.example.ui.notifications.TradingNotificationHelper
import com.example.ui.screens.PnLDashboardScreen
import com.example.ui.screens.SettingsDocsScreen
import com.example.ui.screens.StrategyRiskScreen
import com.example.ui.screens.TerminalScreen
import com.example.ui.theme.LocalTradingColors
import com.example.viewmodel.LocalUiState
import com.example.viewmodel.TradingViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val android.content.Context.accountPreferencesDataStore by preferencesDataStore(name = "account_settings")

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
fun AppNavigation(localUserId: String = "local_trader") {
    LocalTradingShell(
        currentUserId = localUserId,
        displayName = "Local Trader"
    )
}

@Composable
private fun LocalTradingShell(
    currentUserId: String,
    displayName: String
) {
    val viewModel: TradingViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val dao = TradingDatabase.getInstance(app).tradingDao()
                TradingViewModel(
                    localRepository = LocalTradingRepository(dao),
                    accountPreferences = app.accountPreferencesDataStore,
                    credentialVault = SecureCredentialVault(app),
                    telegramNotifier = TelegramNotifier(),
                    backendClient = BackendConnectorClient(),
                    notificationHelper = TradingNotificationHelper(app),
                    currentUserId = currentUserId,
                    userDisplayName = displayName,
                    userEmail = null
                )
            }
        }
    )

    val tradingColors = LocalTradingColors.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val terminalState by viewModel.terminalState.collectAsStateWithLifecycle()
    val databaseStats by viewModel.databaseStats.collectAsStateWithLifecycle()
    val profileState by viewModel.profileState.collectAsStateWithLifecycle()
    val strategiesState by viewModel.strategiesState.collectAsStateWithLifecycle()
    val riskPolicyState by viewModel.riskPolicyState.collectAsStateWithLifecycle()
    val tradesState by viewModel.tradesState.collectAsStateWithLifecycle()
    val dailySummariesState by viewModel.dailySummariesState.collectAsStateWithLifecycle()
    val integrationSettings by viewModel.integrationSettings.collectAsStateWithLifecycle()
    val selectedAccountType by viewModel.selectedAccountType.collectAsStateWithLifecycle()
    val backendHealth by viewModel.backendHealth.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val mlModels by viewModel.mlModels.collectAsStateWithLifecycle()
    val activeMlModel by viewModel.activeMlModel.collectAsStateWithLifecycle()
    val isMlBusy by viewModel.isMlBusy.collectAsStateWithLifecycle()
    val backendOrders by viewModel.backendOrders.collectAsStateWithLifecycle()
    val selectedOrderEvents by viewModel.selectedOrderEvents.collectAsStateWithLifecycle()
    val analyticsModeFilter by viewModel.analyticsModeFilter.collectAsStateWithLifecycle()
    val performanceReport by viewModel.performanceReport.collectAsStateWithLifecycle()
    val backtestReport by viewModel.backtestReport.collectAsStateWithLifecycle()
    val isBacktestBusy by viewModel.isBacktestBusy.collectAsStateWithLifecycle()
    val diagnosticsReport by viewModel.diagnosticsReport.collectAsStateWithLifecycle()
    val isDiagnosticsBusy by viewModel.isDiagnosticsBusy.collectAsStateWithLifecycle()
    val liveReadinessReport by viewModel.liveReadinessReport.collectAsStateWithLifecycle()

    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    val userProfile = (profileState as? LocalUiState.Success)?.data
    val strategies = (strategiesState as? LocalUiState.Success)?.data.orEmpty()
    val activeStrategy = strategies.firstOrNull { it.active }
        ?: strategies.firstOrNull()
        ?: StrategyConfig(userId = currentUserId)
    val riskPolicy = (riskPolicyState as? LocalUiState.Success)?.data
        ?: RiskPolicy(userId = currentUserId)
    val trades = (tradesState as? LocalUiState.Success)?.data.orEmpty()
    val dailySummaries = (dailySummariesState as? LocalUiState.Success)?.data.orEmpty()
    val todayDateKey = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }

    val localDbError = listOf(profileState, strategiesState, riskPolicyState, tradesState)
        .filterIsInstance<LocalUiState.Error>()
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
                                onReconcileOrders = { viewModel.reconcileBackendOrders() }
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
                                onSelectStrategyModule = viewModel::selectStrategyModule,
                                onCheckMlDrift = viewModel::checkMlModelDrift,
                                onRollbackMlModel = { targetId -> viewModel.rollbackMlModel(targetId) },
                                backtestReport = backtestReport,
                                isBacktestBusy = isBacktestBusy,
                                onRunBacktest = viewModel::runWalkForwardBacktest
                            )
                        }
                        composable(NavRoutes.PNL) {
                            PnLDashboardScreen(
                                dailySummaries = dailySummaries,
                                trades = trades,
                                todayDateKey = todayDateKey,
                                performanceReport = performanceReport,
                                analyticsModeFilter = analyticsModeFilter,
                                onSelectAnalyticsMode = viewModel::setAnalyticsModeFilter,
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
                                selectedAccountType = selectedAccountType,
                                onSelectAccountType = viewModel::selectAccountType,
                                userProfile = userProfile,
                                databaseStats = databaseStats,
                                settings = integrationSettings,
                                backendHealth = backendHealth,
                                riskPolicy = riskPolicy,
                                liveReadiness = liveReadinessReport,
                                diagnosticsReport = diagnosticsReport,
                                isDiagnosticsBusy = isDiagnosticsBusy,
                                onRunDiagnostics = viewModel::runSystemDiagnostics,
                                onSwitchMode = viewModel::switchExecutionMode,
                                onResetBalance = viewModel::resetSimulationBalance,
                                onSaveIntegrations = viewModel::saveIntegrationsAndTestBackend,
                                onTestBackend = viewModel::testBackendConnection,
                                onSendTelegramTest = viewModel::sendTelegramTest,
                                onSendLocalNotificationTest = viewModel::sendLocalNotificationTest,
                                onUpdateTraderName = viewModel::updateLocalTraderName
                            )
                        }
                    }

                    if (!localDbError.isNullOrBlank()) {
                        Surface(
                            color = tradingColors.bearishContainer,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "Local SQLite Error: $localDbError",
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
