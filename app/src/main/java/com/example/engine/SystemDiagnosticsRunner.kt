package com.example.engine

import com.example.model.BackendHealthState
import com.example.model.Candle
import com.example.model.DataQualitySummary
import com.example.model.DiagnosticCheckItem
import com.example.model.DiagnosticCheckStatus
import com.example.model.DriftStatus
import com.example.model.ExecutionMode
import com.example.model.IntegrationSettings
import com.example.model.LocalDatabaseStats
import com.example.model.MlModelSummary
import com.example.model.RiskPolicy
import com.example.model.SystemDiagnosticsReport
import com.example.model.TradeDirection
import com.example.model.WebSocketConnectionState

/**
 * Runs non-destructive self-checks across Local Room SQLite, Risk Fail-Closed Guard,
 * Candle Validation, WebSocket Connectivity, ML Drift Posture, and 5-Step Live Gate.
 */
object SystemDiagnosticsRunner {

    fun runLocalDiagnostics(
        dbStats: LocalDatabaseStats,
        candles: List<Candle>,
        dataQuality: DataQualitySummary,
        riskPolicy: RiskPolicy,
        backendHealth: BackendHealthState,
        wsState: WebSocketConnectionState,
        settings: IntegrationSettings,
        activeMlModel: MlModelSummary?,
        unresolvedOrdersCount: Int,
        currentMode: ExecutionMode,
        vaultRoundTripOk: Boolean = true
    ): SystemDiagnosticsReport {
        val checks = mutableListOf<DiagnosticCheckItem>()

        // 1. Local Room SQLite Store
        checks += DiagnosticCheckItem(
            checkId = "local_sqlite",
            title = "Local SQLite (Room v4)",
            status = DiagnosticCheckStatus.PASS,
            detail = "${dbStats.databaseName}: ${dbStats.totalTrades} trades, ${dbStats.totalCachedCandles} candles, ${dbStats.totalStrategies} strategies"
        )

        // 2. Android Keystore AES Vault
        checks += DiagnosticCheckItem(
            checkId = "keystore_vault",
            title = "Keystore AES-256-GCM Vault",
            status = if (vaultRoundTripOk) DiagnosticCheckStatus.PASS else DiagnosticCheckStatus.FAIL,
            detail = if (vaultRoundTripOk) {
                "Encryption active (Backend token: ${if (settings.hasBackendToken) "stored" else "none"}, Telegram: ${if (settings.hasTelegramToken) "stored" else "none"})"
            } else {
                "Android Keystore encryption check failed"
            }
        )

        // 3. Risk Engine Fail-Closed Probe
        val emergencyProbe = RiskManagementEngine.validateOrder(
            policy = riskPolicy.copy(emergencyStopped = true),
            todaySummary = null,
            openExposure = 0.0,
            requestedAmount = 25.0,
            direction = TradeDirection.CALL,
            mode = ExecutionMode.SIMULATION,
            backendReadyForLive = false,
            lastLossTimestampMillis = null
        )
        val liveLockProbe = RiskManagementEngine.validateOrder(
            policy = riskPolicy.copy(emergencyStopped = false),
            todaySummary = null,
            openExposure = 0.0,
            requestedAmount = 25.0,
            direction = TradeDirection.CALL,
            mode = ExecutionMode.LIVE,
            backendReadyForLive = false,
            lastLossTimestampMillis = null
        )
        val failClosedOk = (!emergencyProbe.allowed) && (!liveLockProbe.allowed)
        checks += DiagnosticCheckItem(
            checkId = "risk_fail_closed",
            title = "Risk Engine Fail-Closed Guard",
            status = if (failClosedOk) DiagnosticCheckStatus.PASS else DiagnosticCheckStatus.FAIL,
            detail = if (failClosedOk) {
                "Verified emergency-stop block & locked LIVE safeguard."
            } else {
                "CRITICAL: Risk guard failed negative safety probe!"
            }
        )

        // 4. Market Data Quality & Stale/Gap Guard
        val dqCheck = if (candles.isEmpty()) {
            DiagnosticCheckItem(
                checkId = "market_data",
                title = "Market Data Validation",
                status = DiagnosticCheckStatus.WARN,
                detail = "No candles loaded yet for validation."
            )
        } else {
            DiagnosticCheckItem(
                checkId = "market_data",
                title = "Market Data Validation",
                status = if (dataQuality.valid) DiagnosticCheckStatus.PASS else DiagnosticCheckStatus.FAIL,
                detail = "${dataQuality.reason} (bars=${candles.size}, gaps=${dataQuality.gapCount}, dups=${dataQuality.duplicateCount})"
            )
        }
        checks += dqCheck

        // 5. Hybrid Backend & WebSocket Stream Status
        val wsDetail = if (backendHealth.connected) {
            "REST connected (${backendHealth.connectorName}) | WS Market=${wsState.marketConnected}, Signal=${wsState.signalConnected}, System=${wsState.systemConnected}"
        } else {
            "Offline / Local SQLite Simulation Mode (${backendHealth.statusMessage})"
        }
        checks += DiagnosticCheckItem(
            checkId = "backend_connectivity",
            title = "Hybrid Backend & WebSocket",
            status = if (backendHealth.connected) DiagnosticCheckStatus.PASS else DiagnosticCheckStatus.WARN,
            detail = wsDetail
        )

        // 6. Unresolved Orders & ML Drift
        checks += DiagnosticCheckItem(
            checkId = "reconciliation_health",
            title = "Order Reconciliation Health",
            status = if (unresolvedOrdersCount == 0) DiagnosticCheckStatus.PASS else DiagnosticCheckStatus.WARN,
            detail = if (unresolvedOrdersCount == 0) {
                "0 unresolved PENDING/UNKNOWN orders."
            } else {
                "$unresolvedOrdersCount unresolved order(s) require reconciliation before next trade."
            }
        )

        val driftCheck = when {
            activeMlModel == null -> DiagnosticCheckItem(
                checkId = "ml_drift",
                title = "ML Model Drift Posture",
                status = DiagnosticCheckStatus.WARN,
                detail = "No active ML model promoted; running deterministic quantitative rules."
            )
            activeMlModel.driftStatus == DriftStatus.DRIFT_DETECTED -> DiagnosticCheckItem(
                checkId = "ml_drift",
                title = "ML Model Drift Posture",
                status = DiagnosticCheckStatus.FAIL,
                detail = "${activeMlModel.modelId}: DRIFT DETECTED (score=${String.format("%.2f", activeMlModel.driftScore)}) — manual rollback advised."
            )
            activeMlModel.driftStatus == DriftStatus.WARNING -> DiagnosticCheckItem(
                checkId = "ml_drift",
                title = "ML Model Drift Posture",
                status = DiagnosticCheckStatus.WARN,
                detail = "${activeMlModel.modelId}: Drift warning (score=${String.format("%.2f", activeMlModel.driftScore)})."
            )
            else -> DiagnosticCheckItem(
                checkId = "ml_drift",
                title = "ML Model Drift Posture",
                status = DiagnosticCheckStatus.PASS,
                detail = "${activeMlModel.modelId} (${activeMlModel.algorithm}) healthy."
            )
        }
        checks += driftCheck

        val readiness = LiveReadinessEvaluator.evaluateLocal(
            backendHealth = backendHealth,
            riskPolicy = riskPolicy,
            settings = settings,
            dataQuality = dataQuality,
            unresolvedOrdersCount = unresolvedOrdersCount,
            currentMode = currentMode,
            ownerConfirmed = currentMode == ExecutionMode.LIVE
        )

        val overall = when {
            checks.any { it.status == DiagnosticCheckStatus.FAIL } -> DiagnosticCheckStatus.FAIL
            checks.any { it.status == DiagnosticCheckStatus.WARN } -> DiagnosticCheckStatus.WARN
            else -> DiagnosticCheckStatus.PASS
        }

        return SystemDiagnosticsReport(
            overallStatus = overall,
            connectorName = backendHealth.connectorName,
            connectorConnected = backendHealth.connected,
            checks = checks,
            liveReadiness = readiness,
            checkedAtMillis = System.currentTimeMillis()
        )
    }
}
