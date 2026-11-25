package com.example.data.local

import com.example.data.repository.TradingCloudRepository
import com.example.model.DailyPnLSummary
import com.example.model.RiskPolicy
import com.example.model.StrategyConfig
import com.example.model.TradeRecord
import com.example.model.TradeStatus
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@JsonClass(generateAdapter = true)
data class QueuedTradeCreatePayload(
    val id: String,
    val userId: String,
    val instrument: String,
    val timeframe: String,
    val direction: String,
    val mode: String,
    val amount: Double,
    val payoutRate: Double,
    val entryPrice: Double,
    val exitPrice: Double,
    val status: String,
    val pnl: Double,
    val strategyName: String,
    val confidenceScore: Int,
    val calibratedProbability: Double,
    val explanation: String
)

@JsonClass(generateAdapter = true)
data class QueuedTradeSettlePayload(
    val tradeId: String,
    val exitPrice: Double,
    val status: String,
    val pnl: Double
)

@JsonClass(generateAdapter = true)
data class QueuedDailySummaryPayload(
    val dateKey: String,
    val userId: String,
    val totalTrades: Int,
    val wins: Int,
    val losses: Int,
    val netPnl: Double,
    val consecutiveLosses: Int,
    val peakDrawdown: Double
)

@JsonClass(generateAdapter = true)
data class QueuedStrategyPayload(
    val id: String,
    val userId: String,
    val name: String,
    val instrument: String,
    val timeframe: String,
    val tradeAmount: Double,
    val expirySeconds: Int,
    val rsiPeriod: Int,
    val rsiOversold: Int,
    val rsiOverbought: Int,
    val emaFast: Int,
    val emaSlow: Int,
    val macdFast: Int,
    val macdSlow: Int,
    val macdSignal: Int,
    val bbPeriod: Int,
    val bbStdDev: Double,
    val minConfidence: Int,
    val active: Boolean
)

@JsonClass(generateAdapter = true)
data class QueuedRiskPolicyPayload(
    val userId: String,
    val maxTradeAmount: Double,
    val maxDailyLoss: Double,
    val maxConsecutiveLosses: Int,
    val maxDailyTrades: Int,
    val maxPortfolioExposure: Double,
    val cooldownMinutes: Int,
    val emergencyStopped: Boolean,
    val autoTradeEnabled: Boolean
)

data class SyncFlushResult(
    val syncedCount: Int = 0,
    val failedCount: Int = 0,
    val lastError: String? = null
)

class OfflineSyncQueueManager(
    private val tradingDao: TradingDao,
    private val cloudRepository: TradingCloudRepository
) {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val tradeCreateAdapter = moshi.adapter(QueuedTradeCreatePayload::class.java)
    private val tradeSettleAdapter = moshi.adapter(QueuedTradeSettlePayload::class.java)
    private val summaryAdapter = moshi.adapter(QueuedDailySummaryPayload::class.java)
    private val strategyAdapter = moshi.adapter(QueuedStrategyPayload::class.java)
    private val riskAdapter = moshi.adapter(QueuedRiskPolicyPayload::class.java)
    private val flushMutex = Mutex()

    suspend fun enqueueTradeCreate(
        tradeId: String,
        trade: TradeRecord,
        clientModifiedMillis: Long = System.currentTimeMillis()
    ) {
        val payload = QueuedTradeCreatePayload(
            id = tradeId,
            userId = trade.userId,
            instrument = trade.instrument,
            timeframe = trade.timeframe,
            direction = trade.direction,
            mode = trade.mode,
            amount = trade.amount,
            payoutRate = trade.payoutRate,
            entryPrice = trade.entryPrice,
            exitPrice = trade.exitPrice,
            status = trade.status,
            pnl = trade.pnl,
            strategyName = trade.strategyName,
            confidenceScore = trade.confidenceScore,
            calibratedProbability = trade.calibratedProbability,
            explanation = trade.explanation
        )
        enqueue(
            queueId = "trade_create_$tradeId",
            userId = trade.userId,
            entityType = SyncEntityType.TRADE_CREATE,
            documentId = tradeId,
            payloadJson = tradeCreateAdapter.toJson(payload),
            clientModifiedMillis = clientModifiedMillis
        )
    }

    suspend fun enqueueTradeSettle(
        userId: String,
        tradeId: String,
        exitPrice: Double,
        status: TradeStatus,
        pnl: Double,
        clientModifiedMillis: Long = System.currentTimeMillis()
    ) {
        val payload = QueuedTradeSettlePayload(
            tradeId = tradeId,
            exitPrice = exitPrice,
            status = status.name,
            pnl = pnl
        )
        enqueue(
            queueId = "trade_settle_$tradeId",
            userId = userId,
            entityType = SyncEntityType.TRADE_SETTLE,
            documentId = tradeId,
            payloadJson = tradeSettleAdapter.toJson(payload),
            clientModifiedMillis = clientModifiedMillis
        )
    }

    suspend fun enqueueDailySummary(
        summary: DailyPnLSummary,
        clientModifiedMillis: Long = System.currentTimeMillis()
    ) {
        val dateKey = summary.dateKey.ifBlank { cloudRepository.todayDateKey() }
        val payload = QueuedDailySummaryPayload(
            dateKey = dateKey,
            userId = summary.userId,
            totalTrades = summary.totalTrades,
            wins = summary.wins,
            losses = summary.losses,
            netPnl = summary.netPnl,
            consecutiveLosses = summary.consecutiveLosses,
            peakDrawdown = summary.peakDrawdown
        )
        enqueue(
            queueId = "summary_$dateKey",
            userId = summary.userId,
            entityType = SyncEntityType.DAILY_SUMMARY,
            documentId = dateKey,
            payloadJson = summaryAdapter.toJson(payload),
            clientModifiedMillis = clientModifiedMillis
        )
    }

    suspend fun enqueueStrategy(
        strategy: StrategyConfig,
        userId: String,
        clientModifiedMillis: Long = System.currentTimeMillis()
    ) {
        val docId = strategy.id.ifBlank { "default_ema_rsi" }
        val payload = QueuedStrategyPayload(
            id = docId,
            userId = userId,
            name = strategy.name,
            instrument = strategy.instrument,
            timeframe = strategy.timeframe,
            tradeAmount = strategy.tradeAmount,
            expirySeconds = strategy.expirySeconds,
            rsiPeriod = strategy.rsiPeriod,
            rsiOversold = strategy.rsiOversold,
            rsiOverbought = strategy.rsiOverbought,
            emaFast = strategy.emaFast,
            emaSlow = strategy.emaSlow,
            macdFast = strategy.macdFast,
            macdSlow = strategy.macdSlow,
            macdSignal = strategy.macdSignal,
            bbPeriod = strategy.bbPeriod,
            bbStdDev = strategy.bbStdDev,
            minConfidence = strategy.minConfidence,
            active = strategy.active
        )
        enqueue(
            queueId = "strategy_$docId",
            userId = userId,
            entityType = SyncEntityType.STRATEGY,
            documentId = docId,
            payloadJson = strategyAdapter.toJson(payload),
            clientModifiedMillis = clientModifiedMillis
        )
    }

    suspend fun enqueueRiskPolicy(
        policy: RiskPolicy,
        userId: String,
        clientModifiedMillis: Long = System.currentTimeMillis()
    ) {
        val payload = QueuedRiskPolicyPayload(
            userId = userId,
            maxTradeAmount = policy.maxTradeAmount,
            maxDailyLoss = policy.maxDailyLoss,
            maxConsecutiveLosses = policy.maxConsecutiveLosses,
            maxDailyTrades = policy.maxDailyTrades,
            maxPortfolioExposure = policy.maxPortfolioExposure,
            cooldownMinutes = policy.cooldownMinutes,
            emergencyStopped = policy.emergencyStopped,
            autoTradeEnabled = policy.autoTradeEnabled
        )
        enqueue(
            queueId = "risk_policy_default",
            userId = userId,
            entityType = SyncEntityType.RISK_POLICY,
            documentId = "default",
            payloadJson = riskAdapter.toJson(payload),
            clientModifiedMillis = clientModifiedMillis
        )
    }

    private suspend fun enqueue(
        queueId: String,
        userId: String,
        entityType: String,
        documentId: String,
        payloadJson: String,
        clientModifiedMillis: Long
    ) {
        withContext(Dispatchers.IO) {
            tradingDao.upsertSyncItem(
                SyncQueueEntity(
                    queueId = queueId,
                    userId = userId,
                    entityType = entityType,
                    documentId = documentId,
                    payloadJson = payloadJson,
                    clientModifiedMillis = clientModifiedMillis,
                    retryCount = 0,
                    nextRetryMillis = 0L,
                    lastError = null,
                    status = SyncQueueStatus.PENDING
                )
            )
        }
    }

    suspend fun flushPending(userId: String, forceRetryFailed: Boolean = false): SyncFlushResult {
        return flushMutex.withLock {
            val now = System.currentTimeMillis()
            if (forceRetryFailed) {
                withContext(Dispatchers.IO) { tradingDao.resetFailedSyncItems(userId) }
            }
            val dueItems = withContext(Dispatchers.IO) {
                tradingDao.getDueSyncItems(userId = userId, nowMillis = now, limit = 25)
            }
            var syncedCount = 0
            var failedCount = 0
            var lastError: String? = null

            for (item in dueItems) {
                val res = dispatchItem(item)
                if (res.isSuccess) {
                    syncedCount++
                    withContext(Dispatchers.IO) {
                        tradingDao.markSyncItemDone(item.queueId)
                        if (item.entityType == SyncEntityType.TRADE_CREATE ||
                            item.entityType == SyncEntityType.TRADE_SETTLE
                        ) {
                            tradingDao.markSynced(item.documentId)
                        }
                    }
                } else {
                    failedCount++
                    val err = res.exceptionOrNull()?.localizedMessage ?: "Firestore sync failed"
                    lastError = err
                    val nextRetries = item.retryCount + 1
                    val exhausted = nextRetries >= MAX_RETRIES
                    val nextStatus = if (exhausted) SyncQueueStatus.FAILED_PERMANENT else SyncQueueStatus.PENDING
                    val nextRetryAt = computeNextRetryMillis(System.currentTimeMillis(), nextRetries)
                    withContext(Dispatchers.IO) {
                        tradingDao.markSyncItemRetry(
                            queueId = item.queueId,
                            retryCount = nextRetries,
                            nextRetryMillis = nextRetryAt,
                            lastError = err.take(300),
                            status = nextStatus
                        )
                    }
                }
            }

            withContext(Dispatchers.IO) {
                tradingDao.pruneSyncedItems(System.currentTimeMillis() - PRUNE_AFTER_MS)
            }
            SyncFlushResult(syncedCount = syncedCount, failedCount = failedCount, lastError = lastError)
        }
    }

    private suspend fun dispatchItem(item: SyncQueueEntity): Result<Unit> {
        return try {
            when (item.entityType) {
                SyncEntityType.TRADE_CREATE -> {
                    val p = tradeCreateAdapter.fromJson(item.payloadJson)
                        ?: return Result.failure(IllegalArgumentException("Invalid trade create JSON"))
                    val record = TradeRecord(
                        id = p.id,
                        userId = p.userId,
                        instrument = p.instrument,
                        timeframe = p.timeframe,
                        direction = p.direction,
                        mode = p.mode,
                        amount = p.amount,
                        payoutRate = p.payoutRate,
                        entryPrice = p.entryPrice,
                        exitPrice = p.exitPrice,
                        status = TradeStatus.toFirestoreStatus(p.status),
                        pnl = p.pnl,
                        strategyName = p.strategyName,
                        confidenceScore = p.confidenceScore,
                        calibratedProbability = p.calibratedProbability,
                        explanation = p.explanation
                    )
                    cloudRepository.createTrade(record).map { Unit }
                }
                SyncEntityType.TRADE_SETTLE -> {
                    val p = tradeSettleAdapter.fromJson(item.payloadJson)
                        ?: return Result.failure(IllegalArgumentException("Invalid trade settle JSON"))
                    cloudRepository.settleTrade(
                        tradeId = p.tradeId,
                        exitPrice = p.exitPrice,
                        status = TradeStatus.safeValueOf(p.status),
                        pnl = p.pnl
                    )
                }
                SyncEntityType.DAILY_SUMMARY -> {
                    val p = summaryAdapter.fromJson(item.payloadJson)
                        ?: return Result.failure(IllegalArgumentException("Invalid daily summary JSON"))
                    cloudRepository.upsertDailySummary(
                        DailyPnLSummary(
                            id = p.dateKey,
                            userId = p.userId,
                            dateKey = p.dateKey,
                            totalTrades = p.totalTrades,
                            wins = p.wins,
                            losses = p.losses,
                            netPnl = p.netPnl,
                            consecutiveLosses = p.consecutiveLosses,
                            peakDrawdown = p.peakDrawdown
                        )
                    )
                }
                SyncEntityType.STRATEGY -> {
                    val p = strategyAdapter.fromJson(item.payloadJson)
                        ?: return Result.failure(IllegalArgumentException("Invalid strategy JSON"))
                    cloudRepository.saveStrategy(
                        StrategyConfig(
                            id = p.id,
                            userId = p.userId,
                            name = p.name,
                            instrument = p.instrument,
                            timeframe = p.timeframe,
                            tradeAmount = p.tradeAmount,
                            expirySeconds = p.expirySeconds,
                            rsiPeriod = p.rsiPeriod,
                            rsiOversold = p.rsiOversold,
                            rsiOverbought = p.rsiOverbought,
                            emaFast = p.emaFast,
                            emaSlow = p.emaSlow,
                            macdFast = p.macdFast,
                            macdSlow = p.macdSlow,
                            macdSignal = p.macdSignal,
                            bbPeriod = p.bbPeriod,
                            bbStdDev = p.bbStdDev,
                            minConfidence = p.minConfidence,
                            active = p.active
                        )
                    ).map { Unit }
                }
                SyncEntityType.RISK_POLICY -> {
                    val p = riskAdapter.fromJson(item.payloadJson)
                        ?: return Result.failure(IllegalArgumentException("Invalid risk policy JSON"))
                    cloudRepository.saveRiskPolicy(
                        RiskPolicy(
                            id = "default",
                            userId = p.userId,
                            maxTradeAmount = p.maxTradeAmount,
                            maxDailyLoss = p.maxDailyLoss,
                            maxConsecutiveLosses = p.maxConsecutiveLosses,
                            maxDailyTrades = p.maxDailyTrades,
                            maxPortfolioExposure = p.maxPortfolioExposure,
                            cooldownMinutes = p.cooldownMinutes,
                            emergencyStopped = p.emergencyStopped,
                            autoTradeEnabled = p.autoTradeEnabled
                        )
                    )
                }
                else -> Result.failure(IllegalArgumentException("Unknown entityType ${item.entityType}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        const val MAX_RETRIES = 10
        private const val PRUNE_AFTER_MS = 24L * 60L * 60L * 1000L

        fun computeNextRetryMillis(nowMillis: Long, retryCount: Int): Long {
            val shift = (retryCount - 1).coerceIn(0, 8)
            val delayMs = minOf(300_000L, 2_000L * (1L shl shift))
            return nowMillis + delayMs
        }
    }
}
