package com.example.data.repository

import com.example.data.local.LocalDailySummaryEntity
import com.example.data.local.LocalRiskPolicyEntity
import com.example.data.local.LocalStrategyEntity
import com.example.data.local.LocalTradeCacheEntity
import com.example.data.local.LocalUserProfileEntity
import com.example.data.local.TradingDao
import com.example.model.DailyPnLSummary
import com.example.model.ExecutionMode
import com.example.model.RiskPolicy
import com.example.model.StrategyConfig
import com.example.model.TradeRecord
import com.example.model.TradeStatus
import com.example.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Local-only source of truth for trading state.
 * This repository has no Firebase, HTTP, or cloud dependencies.
 */
class LocalTradingRepository(private val dao: TradingDao) {

    fun todayDateKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    suspend fun ensureLocalProfile(displayName: String = "Trader") {
        if (dao.getUserProfile() == null) {
            dao.upsertUserProfile(LocalUserProfileEntity(displayName = displayName.take(100).ifBlank { "Trader" }))
        }
        if (dao.getRiskPolicy() == null) {
            dao.upsertRiskPolicy(RiskPolicy().toLocal())
        }
        if (dao.getStrategy("default_ema_rsi") == null) {
            dao.upsertStrategy(StrategyConfig(id = "default_ema_rsi", userId = LOCAL_USER_ID).toLocal())
        }
    }

    suspend fun ensureUserBootstrap(userId: String, displayName: String, email: String?): Result<Unit> = runCatching { ensureLocalProfile(displayName) }

    fun observeUserProfile(userId: String): Flow<UserProfile?> = observeUserProfile()

    fun observeUserProfile(): Flow<UserProfile?> = dao.observeUserProfile().map { row ->
        row?.let { UserProfile(
            id = it.id, userId = it.userId, displayName = it.displayName,
            executionMode = it.executionMode, simulationBalance = it.simulationBalance,
            updatedAtMillis = it.updatedAtMillis
        ) }
    }

    fun observeStrategies(userId: String): Flow<List<StrategyConfig>> =
        dao.observeStrategies(userId).map { rows -> rows.map { it.toModel() } }

    fun observeRiskPolicy(userId: String): Flow<RiskPolicy?> = observeRiskPolicy()

    fun observeRiskPolicy(): Flow<RiskPolicy?> = dao.observeRiskPolicy().map { it?.toModel() }

    fun observeTrades(userId: String): Flow<List<TradeRecord>> =
        dao.observeAllLocalTrades(userId).map { rows -> rows.map { it.toModel() } }

    fun observeDailySummaries(userId: String): Flow<List<DailyPnLSummary>> =
        dao.observeDailySummaries(userId).map { rows -> rows.map { it.toModel() } }

    suspend fun saveStrategy(strategy: StrategyConfig): Result<String> = runCatching {
        val id = strategy.id.ifBlank { "strategy_${UUID.randomUUID()}" }
        dao.upsertStrategy(strategy.copy(id = id).toLocal())
        id
    }

    suspend fun saveRiskPolicy(policy: RiskPolicy): Result<Unit> = runCatching {
        dao.upsertRiskPolicy(policy.toLocal())
    }

    suspend fun createTrade(trade: TradeRecord): Result<String> = runCatching {
        val id = trade.id.ifBlank { "trade_${UUID.randomUUID()}" }
        dao.upsertTrade(trade.copy(id = id, userId = LOCAL_USER_ID).toLocal(System.currentTimeMillis()))
        id
    }

    suspend fun settleTrade(tradeId: String, exitPrice: Double, status: TradeStatus, pnl: Double): Result<Unit> =
        runCatching { dao.settleLocalTrade(tradeId, exitPrice.coerceAtLeast(0.0), status.name, pnl) }

    suspend fun upsertDailySummary(summary: DailyPnLSummary): Result<Unit> = runCatching {
        dao.upsertDailySummary(summary.toLocal())
    }

    suspend fun updateUserBalanceAndMode(
        simulationBalance: Double,
        executionMode: ExecutionMode,
    ): Result<Unit> = runCatching {
        val old = dao.getUserProfile() ?: LocalUserProfileEntity()
        dao.upsertUserProfile(old.copy(
            simulationBalance = simulationBalance.coerceIn(0.0, 10_000_000.0),
            executionMode = executionMode.name,
            updatedAtMillis = System.currentTimeMillis()
        ))
    }

    private fun StrategyConfig.toLocal() = LocalStrategyEntity(
        id = id, userId = LOCAL_USER_ID, name = name, instrument = instrument, timeframe = timeframe,
        tradeAmount = tradeAmount, expirySeconds = expirySeconds, rsiPeriod = rsiPeriod,
        rsiOversold = rsiOversold, rsiOverbought = rsiOverbought, emaFast = emaFast, emaSlow = emaSlow,
        macdFast = macdFast, macdSlow = macdSlow, macdSignal = macdSignal, bbPeriod = bbPeriod,
        bbStdDev = bbStdDev, minConfidence = minConfidence, active = active,
        updatedAtMillis = System.currentTimeMillis()
    )

    private fun LocalStrategyEntity.toModel() = StrategyConfig(
        id = id, userId = userId, name = name, instrument = instrument, timeframe = timeframe,
        tradeAmount = tradeAmount, expirySeconds = expirySeconds, rsiPeriod = rsiPeriod,
        rsiOversold = rsiOversold, rsiOverbought = rsiOverbought, emaFast = emaFast, emaSlow = emaSlow,
        macdFast = macdFast, macdSlow = macdSlow, macdSignal = macdSignal, bbPeriod = bbPeriod,
        bbStdDev = bbStdDev, minConfidence = minConfidence, active = active, updatedAtMillis = updatedAtMillis
    )

    private fun RiskPolicy.toLocal() = LocalRiskPolicyEntity(
        userId = LOCAL_USER_ID, maxTradeAmount = maxTradeAmount, maxDailyLoss = maxDailyLoss,
        maxConsecutiveLosses = maxConsecutiveLosses, maxDailyTrades = maxDailyTrades,
        maxPortfolioExposure = maxPortfolioExposure, cooldownMinutes = cooldownMinutes,
        emergencyStopped = emergencyStopped, autoTradeEnabled = autoTradeEnabled,
        updatedAtMillis = System.currentTimeMillis()
    )

    private fun LocalRiskPolicyEntity.toModel() = RiskPolicy(
        id = id, userId = userId, maxTradeAmount = maxTradeAmount, maxDailyLoss = maxDailyLoss,
        maxConsecutiveLosses = maxConsecutiveLosses, maxDailyTrades = maxDailyTrades,
        maxPortfolioExposure = maxPortfolioExposure, cooldownMinutes = cooldownMinutes,
        emergencyStopped = emergencyStopped, autoTradeEnabled = autoTradeEnabled, updatedAtMillis = updatedAtMillis
    )

    private fun TradeRecord.toLocal(createdAt: Long) = LocalTradeCacheEntity(
        tradeId = id, userId = LOCAL_USER_ID, instrument = instrument, timeframe = timeframe,
        direction = direction, mode = mode, amount = amount, payoutRate = payoutRate,
        entryPrice = entryPrice, exitPrice = exitPrice, status = status, pnl = pnl,
        strategyName = strategyName, confidenceScore = confidenceScore,
        calibratedProbability = calibratedProbability, explanation = explanation,
        createdAtMillis = createdAt
    )

    private fun LocalTradeCacheEntity.toModel() = TradeRecord(
        id = tradeId, userId = userId, instrument = instrument, timeframe = timeframe,
        direction = direction, mode = mode, amount = amount, payoutRate = payoutRate,
        entryPrice = entryPrice, exitPrice = exitPrice, status = status, pnl = pnl,
        strategyName = strategyName, confidenceScore = confidenceScore,
        calibratedProbability = calibratedProbability, explanation = explanation, createdAtMillis = createdAtMillis
    )

    private fun DailyPnLSummary.toLocal() = LocalDailySummaryEntity(
        dateKey = dateKey.ifBlank { todayDateKey() }, userId = LOCAL_USER_ID,
        totalTrades = totalTrades, wins = wins, losses = losses, netPnl = netPnl,
        consecutiveLosses = consecutiveLosses, peakDrawdown = peakDrawdown,
        updatedAtMillis = System.currentTimeMillis()
    )

    private fun LocalDailySummaryEntity.toModel() = DailyPnLSummary(
        id = dateKey, userId = userId, dateKey = dateKey, totalTrades = totalTrades,
        wins = wins, losses = losses, netPnl = netPnl,
        consecutiveLosses = consecutiveLosses, peakDrawdown = peakDrawdown
    )

    companion object { const val LOCAL_USER_ID = "local" }
}
