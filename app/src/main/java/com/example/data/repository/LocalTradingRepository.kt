package com.example.data.repository

import com.example.data.local.CachedCandleEntity
import com.example.data.local.LocalDailySummaryEntity
import com.example.data.local.LocalRiskPolicyEntity
import com.example.data.local.LocalStrategyConfigEntity
import com.example.data.local.LocalTradeCacheEntity
import com.example.data.local.LocalUserProfileEntity
import com.example.data.local.TradingDao
import com.example.data.local.TradingDatabase
import com.example.model.Candle
import com.example.model.DailyPnLSummary
import com.example.model.ExecutionMode
import com.example.model.LocalDatabaseStats
import com.example.model.RiskPolicy
import com.example.model.StrategyConfig
import com.example.model.Timeframe
import com.example.model.TradeDirection
import com.example.model.TradeRecord
import com.example.model.TradeStatus
import com.example.model.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class LocalTradingRepository(
    private val tradingDao: TradingDao
) {
    fun todayDateKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    suspend fun ensureUserBootstrap(
        userId: String,
        displayName: String = "Local Trader",
        email: String? = null
    ): Result<Unit> = runLocalWrite {
        val now = System.currentTimeMillis()
        if (tradingDao.getUserProfileOnce(userId) == null) {
            tradingDao.upsertUserProfile(
                LocalUserProfileEntity(
                    userId = userId,
                    displayName = displayName.trim().take(100).ifBlank { "Local Trader" },
                    email = email?.trim()?.take(150),
                    executionMode = ExecutionMode.SIMULATION.name,
                    simulationBalance = 10_000.0,
                    lastSavedAtMillis = now,
                    createdAtMillis = now,
                    updatedAtMillis = now
                )
            )
        }

        if (tradingDao.getRiskPolicyOnce(userId) == null) {
            val defaultPolicy = RiskPolicy(userId = userId, createdAtMillis = now, updatedAtMillis = now)
            tradingDao.upsertRiskPolicy(defaultPolicy.toEntity(userId, now))
        }

        if (tradingDao.getStrategiesOnce(userId).isEmpty()) {
            val starter = StrategyConfig(
                id = "default_ema_rsi_$userId",
                userId = userId,
                name = "EMA-RSI Momentum",
                instrument = "EUR/USD",
                timeframe = "1m",
                tradeAmount = 25.0,
                expirySeconds = 60,
                active = true,
                createdAtMillis = now,
                updatedAtMillis = now
            )
            tradingDao.upsertStrategy(starter.toEntity(userId, now))
        }
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> =
        tradingDao.observeUserProfile(userId).map { it?.toDomain() }

    fun observeStrategies(userId: String): Flow<List<StrategyConfig>> =
        tradingDao.observeStrategies(userId).map { list -> list.map { it.toDomain() } }

    fun observeRiskPolicy(userId: String): Flow<RiskPolicy?> =
        tradingDao.observeRiskPolicy(userId).map { it?.toDomain() }

    fun observeTrades(userId: String): Flow<List<TradeRecord>> =
        tradingDao.observeLocalTrades(userId).map { list -> list.map { it.toDomain() } }

    fun observeDailySummaries(userId: String): Flow<List<DailyPnLSummary>> =
        tradingDao.observeDailySummaries(userId).map { list -> list.map { it.toDomain() } }

    fun observeDatabaseStats(userId: String): Flow<LocalDatabaseStats> = combine(
        tradingDao.observeTradeCount(userId),
        tradingDao.observeCandleCount(),
        tradingDao.observeStrategies(userId),
        tradingDao.observeDailySummaries(userId),
        tradingDao.observeUserProfile(userId)
    ) { tradeCount, candleCount, strategies, summaries, profile ->
        LocalDatabaseStats(
            databaseName = TradingDatabase.DATABASE_NAME,
            totalTrades = tradeCount,
            totalCachedCandles = candleCount,
            totalStrategies = strategies.size,
            totalDailySummaries = summaries.size,
            lastSavedAtMillis = profile?.lastSavedAtMillis ?: profile?.updatedAtMillis
        )
    }

    suspend fun updateUserBalanceAndMode(
        userId: String,
        newBalance: Double,
        mode: ExecutionMode,
        markSavedTimestamp: Boolean = false
    ): Result<Unit> = runLocalWrite {
        val now = System.currentTimeMillis()
        val existing = tradingDao.getUserProfileOnce(userId)
        val updated = LocalUserProfileEntity(
            userId = userId,
            displayName = existing?.displayName ?: "Local Trader",
            email = existing?.email,
            executionMode = mode.name,
            simulationBalance = newBalance.coerceIn(0.0, 10_000_000.0),
            lastSavedAtMillis = if (markSavedTimestamp) now else (existing?.lastSavedAtMillis ?: now),
            createdAtMillis = existing?.createdAtMillis ?: now,
            updatedAtMillis = now
        )
        tradingDao.upsertUserProfile(updated)
    }

    suspend fun updateDisplayName(userId: String, displayName: String): Result<Unit> = runLocalWrite {
        val now = System.currentTimeMillis()
        val cleanName = displayName.trim().take(100).ifBlank { "Local Trader" }
        val existing = tradingDao.getUserProfileOnce(userId)
        val updated = LocalUserProfileEntity(
            userId = userId,
            displayName = cleanName,
            email = existing?.email,
            executionMode = existing?.executionMode ?: ExecutionMode.SIMULATION.name,
            simulationBalance = existing?.simulationBalance ?: 10_000.0,
            lastSavedAtMillis = now,
            createdAtMillis = existing?.createdAtMillis ?: now,
            updatedAtMillis = now
        )
        tradingDao.upsertUserProfile(updated)
    }

    suspend fun saveStrategy(strategy: StrategyConfig): Result<Unit> = runLocalWrite {
        val now = System.currentTimeMillis()
        val uid = strategy.userId.ifBlank { "local_trader" }
        tradingDao.upsertStrategy(strategy.toEntity(uid, now))
    }

    suspend fun saveRiskPolicy(policy: RiskPolicy): Result<Unit> = runLocalWrite {
        val now = System.currentTimeMillis()
        val uid = policy.userId.ifBlank { "local_trader" }
        val existing = tradingDao.getRiskPolicyOnce(uid)
        tradingDao.upsertRiskPolicy(
            policy.toEntity(
                uid = uid,
                now = now,
                createdAt = existing?.createdAtMillis ?: now
            )
        )
    }

    suspend fun createTrade(trade: TradeRecord): Result<String> = runLocalWrite {
        val now = System.currentTimeMillis()
        val uid = trade.userId.ifBlank { "local_trader" }
        val tradeId = trade.id.ifBlank { "sql_${UUID.randomUUID().toString().take(12)}" }
        tradingDao.upsertTrade(
            LocalTradeCacheEntity(
                tradeId = tradeId,
                userId = uid,
                instrument = trade.instrument.trim().take(20).ifBlank { "EUR/USD" },
                timeframe = Timeframe.fromCode(trade.timeframe).code,
                direction = if (trade.direction == TradeDirection.PUT.name) "PUT" else "CALL",
                mode = ExecutionMode.safeValueOf(trade.mode).name,
                amount = trade.amount.coerceIn(1.0, 100_000.0),
                payoutRate = trade.payoutRate.coerceIn(0.1, 2.0),
                entryPrice = trade.entryPrice.coerceAtLeast(0.00001),
                exitPrice = trade.exitPrice.coerceAtLeast(0.0),
                status = TradeStatus.safeValueOf(trade.status).name,
                pnl = trade.pnl.coerceIn(-100_000.0, 200_000.0),
                strategyName = trade.strategyName.trim().take(80).ifBlank { "Strategy" },
                confidenceScore = trade.confidenceScore.coerceIn(0, 100),
                calibratedProbability = trade.calibratedProbability.coerceIn(0.0, 1.0),
                explanation = trade.explanation.take(500),
                createdAtMillis = if (trade.createdAtMillis > 0L) trade.createdAtMillis else now,
                syncedToCloud = true
            )
        )
        tradeId
    }

    suspend fun settleTrade(
        tradeId: String,
        exitPrice: Double,
        status: TradeStatus,
        pnl: Double
    ): Result<Unit> = runLocalWrite {
        tradingDao.updateTradeSettlement(
            tradeId = tradeId,
            exitPrice = exitPrice.coerceAtLeast(0.0),
            status = status.name,
            pnl = pnl.coerceIn(-100_000.0, 200_000.0)
        )
    }

    suspend fun upsertDailySummary(summary: DailyPnLSummary): Result<Unit> = runLocalWrite {
        val now = System.currentTimeMillis()
        val uid = summary.userId.ifBlank { "local_trader" }
        val dateKey = summary.dateKey.ifBlank { todayDateKey() }
        val summaryId = "${uid}_$dateKey"
        val existing = tradingDao.getDailySummaryOnce(summaryId)
        tradingDao.upsertDailySummary(
            LocalDailySummaryEntity(
                summaryId = summaryId,
                userId = uid,
                dateKey = dateKey,
                totalTrades = summary.totalTrades.coerceIn(0, 10_000),
                wins = summary.wins.coerceIn(0, 10_000),
                losses = summary.losses.coerceIn(0, 10_000),
                netPnl = summary.netPnl.coerceIn(-10_000_000.0, 10_000_000.0),
                consecutiveLosses = summary.consecutiveLosses.coerceIn(0, 100),
                peakDrawdown = summary.peakDrawdown.coerceIn(0.0, 10_000_000.0),
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now
            )
        )
    }

    suspend fun cacheCandles(instrument: String, timeframe: String, candles: List<Candle>) {
        withContext(Dispatchers.IO) {
            tradingDao.clearCandles(instrument, timeframe)
            tradingDao.insertCandles(
                candles.map {
                    CachedCandleEntity(
                        instrument = instrument,
                        timeframe = timeframe,
                        timestampMillis = it.timestampMillis,
                        open = it.open,
                        high = it.high,
                        low = it.low,
                        close = it.close,
                        volume = it.volume
                    )
                }
            )
        }
    }

    private suspend inline fun <T> runLocalWrite(crossinline block: suspend () -> T): Result<T> {
        return try {
            val value = withContext(Dispatchers.IO) { block() }
            Result.success(value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        internal fun LocalUserProfileEntity.toDomain(): UserProfile = UserProfile(
            id = userId,
            userId = userId,
            displayName = displayName,
            email = email,
            executionMode = executionMode,
            simulationBalance = simulationBalance,
            lastSavedAtMillis = lastSavedAtMillis,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis
        )

        internal fun LocalStrategyConfigEntity.toDomain(): StrategyConfig = StrategyConfig(
            id = id,
            userId = userId,
            name = name,
            instrument = instrument,
            timeframe = timeframe,
            tradeAmount = tradeAmount,
            expirySeconds = expirySeconds,
            rsiPeriod = rsiPeriod,
            rsiOversold = rsiOversold,
            rsiOverbought = rsiOverbought,
            emaFast = emaFast,
            emaSlow = emaSlow,
            macdFast = macdFast,
            macdSlow = macdSlow,
            macdSignal = macdSignal,
            bbPeriod = bbPeriod,
            bbStdDev = bbStdDev,
            minConfidence = minConfidence,
            active = active,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis
        )

        internal fun StrategyConfig.toEntity(uid: String, now: Long): LocalStrategyConfigEntity =
            LocalStrategyConfigEntity(
                id = id.ifBlank { "default_ema_rsi_$uid" },
                userId = uid,
                name = name.trim().take(80).ifBlank { "Custom Strategy" },
                instrument = instrument.trim().take(20).ifBlank { "EUR/USD" },
                timeframe = Timeframe.fromCode(timeframe).code,
                tradeAmount = tradeAmount.coerceIn(1.0, 100_000.0),
                expirySeconds = expirySeconds.coerceIn(60, 3600),
                rsiPeriod = rsiPeriod.coerceIn(2, 50),
                rsiOversold = rsiOversold.coerceIn(5, 45),
                rsiOverbought = rsiOverbought.coerceIn(55, 95),
                emaFast = emaFast.coerceIn(2, 50),
                emaSlow = emaSlow.coerceIn(5, 200),
                macdFast = macdFast.coerceIn(2, 50),
                macdSlow = macdSlow.coerceIn(5, 100),
                macdSignal = macdSignal.coerceIn(2, 50),
                bbPeriod = bbPeriod.coerceIn(5, 50),
                bbStdDev = bbStdDev.coerceIn(0.5, 4.0),
                minConfidence = minConfidence.coerceIn(50, 99),
                active = active,
                createdAtMillis = if (createdAtMillis > 0L) createdAtMillis else now,
                updatedAtMillis = now
            )

        internal fun LocalRiskPolicyEntity.toDomain(): RiskPolicy = RiskPolicy(
            id = userId,
            userId = userId,
            maxTradeAmount = maxTradeAmount,
            maxDailyLoss = maxDailyLoss,
            maxConsecutiveLosses = maxConsecutiveLosses,
            maxDailyTrades = maxDailyTrades,
            maxPortfolioExposure = maxPortfolioExposure,
            cooldownMinutes = cooldownMinutes,
            emergencyStopped = emergencyStopped,
            autoTradeEnabled = autoTradeEnabled,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis
        )

        internal fun RiskPolicy.toEntity(
            uid: String,
            now: Long,
            createdAt: Long = now
        ): LocalRiskPolicyEntity = LocalRiskPolicyEntity(
            userId = uid,
            maxTradeAmount = maxTradeAmount.coerceIn(1.0, 100_000.0),
            maxDailyLoss = maxDailyLoss.coerceIn(1.0, 1_000_000.0),
            maxConsecutiveLosses = maxConsecutiveLosses.coerceIn(1, 20),
            maxDailyTrades = maxDailyTrades.coerceIn(1, 500),
            maxPortfolioExposure = maxPortfolioExposure.coerceIn(1.0, 1_000_000.0),
            cooldownMinutes = cooldownMinutes.coerceIn(0, 1440),
            emergencyStopped = emergencyStopped,
            autoTradeEnabled = autoTradeEnabled,
            createdAtMillis = if (createdAtMillis > 0L) createdAtMillis else createdAt,
            updatedAtMillis = now
        )

        internal fun LocalTradeCacheEntity.toDomain(): TradeRecord = TradeRecord(
            id = tradeId,
            userId = userId,
            instrument = instrument,
            timeframe = timeframe,
            direction = direction,
            mode = mode,
            amount = amount,
            payoutRate = payoutRate,
            entryPrice = entryPrice,
            exitPrice = exitPrice,
            status = status,
            pnl = pnl,
            strategyName = strategyName,
            confidenceScore = confidenceScore,
            calibratedProbability = calibratedProbability,
            explanation = explanation,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = createdAtMillis
        )

        internal fun LocalDailySummaryEntity.toDomain(): DailyPnLSummary = DailyPnLSummary(
            id = dateKey,
            userId = userId,
            dateKey = dateKey,
            totalTrades = totalTrades,
            wins = wins,
            losses = losses,
            netPnl = netPnl,
            consecutiveLosses = consecutiveLosses,
            peakDrawdown = peakDrawdown,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis
        )
    }
}
