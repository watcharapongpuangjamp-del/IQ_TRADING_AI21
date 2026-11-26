package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "local_strategy_configs")
data class LocalStrategyEntity(@PrimaryKey val id: String, val userId: String, val name: String, val instrument: String, val timeframe: String, val tradeAmount: Double, val expirySeconds: Int, val rsiPeriod: Int, val rsiOversold: Int, val rsiOverbought: Int, val emaFast: Int, val emaSlow: Int, val macdFast: Int, val macdSlow: Int, val macdSignal: Int, val bbPeriod: Int, val bbStdDev: Double, val minConfidence: Int, val active: Boolean, val updatedAtMillis: Long)

@Entity(tableName = "local_risk_policies")
data class LocalRiskPolicyEntity(@PrimaryKey val id: String = "default", val userId: String, val maxTradeAmount: Double, val maxDailyLoss: Double, val maxConsecutiveLosses: Int, val maxDailyTrades: Int, val maxPortfolioExposure: Double, val cooldownMinutes: Int, val emergencyStopped: Boolean, val autoTradeEnabled: Boolean, val updatedAtMillis: Long)

@Entity(tableName = "local_user_profile")
data class LocalUserProfileEntity(@PrimaryKey val id: String = "local", val userId: String = "local", val displayName: String = "Trader", val executionMode: String = "SIMULATION", val simulationBalance: Double = 10000.0, val updatedAtMillis: Long = 0L)

@Entity(tableName = "local_daily_pnl_summaries")
data class LocalDailySummaryEntity(@PrimaryKey val dateKey: String, val userId: String, val totalTrades: Int, val wins: Int, val losses: Int, val netPnl: Double, val consecutiveLosses: Int, val peakDrawdown: Double, val updatedAtMillis: Long)
@Entity(tableName = "cached_candles")
data class CachedCandleEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val instrument: String,
    val timeframe: String,
    val timestampMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

@Entity(tableName = "local_trade_logs")
data class LocalTradeCacheEntity(
    @PrimaryKey val tradeId: String,
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
    val explanation: String,
    val createdAtMillis: Long,
    val syncedToCloud: Boolean
)

@Entity(
    tableName = "sync_queue",
    indices = [Index(value = ["userId", "status", "nextRetryMillis"])]
)
data class SyncQueueEntity(
    @PrimaryKey val queueId: String,
    val userId: String,
    val entityType: String,
    val documentId: String,
    val payloadJson: String,
    val clientModifiedMillis: Long,
    val retryCount: Int = 0,
    val nextRetryMillis: Long = 0L,
    val lastError: String? = null,
    val status: String = SyncQueueStatus.PENDING
)

object SyncQueueStatus {
    const val PENDING = "PENDING"
    const val SYNCED = "SYNCED"
    const val FAILED_PERMANENT = "FAILED_PERMANENT"
}

object SyncEntityType {
    const val TRADE_CREATE = "TRADE_CREATE"
    const val TRADE_SETTLE = "TRADE_SETTLE"
    const val DAILY_SUMMARY = "DAILY_SUMMARY"
    const val STRATEGY = "STRATEGY"
    const val RISK_POLICY = "RISK_POLICY"
}

@Dao
interface TradingDao {
    @Query("SELECT * FROM cached_candles WHERE instrument = :instrument AND timeframe = :timeframe ORDER BY timestampMillis ASC LIMIT 120")
    fun observeCandles(instrument: String, timeframe: String): Flow<List<CachedCandleEntity>>

    @Query("DELETE FROM cached_candles WHERE instrument = :instrument AND timeframe = :timeframe")
    suspend fun clearCandles(instrument: String, timeframe: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandles(candles: List<CachedCandleEntity>)

    @Query("SELECT * FROM local_trade_logs WHERE userId = :userId ORDER BY createdAtMillis DESC LIMIT 100")
    fun observeLocalTrades(userId: String): Flow<List<LocalTradeCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrade(trade: LocalTradeCacheEntity)

    @Query("SELECT * FROM local_trade_logs WHERE userId = :userId ORDER BY createdAtMillis DESC")
    fun observeAllLocalTrades(userId: String): Flow<List<LocalTradeCacheEntity>>

    @Query("UPDATE local_trade_logs SET exitPrice = :exitPrice, status = :status, pnl = :pnl WHERE tradeId = :tradeId")
    suspend fun settleLocalTrade(tradeId: String, exitPrice: Double, status: String, pnl: Double)

    @Query("UPDATE local_trade_logs SET syncedToCloud = 1 WHERE tradeId = :tradeId")
    suspend fun markSynced(tradeId: String)


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStrategy(strategy: LocalStrategyEntity)
    @Query("SELECT * FROM local_strategy_configs WHERE userId = :userId ORDER BY updatedAtMillis DESC")
    fun observeStrategies(userId: String): Flow<List<LocalStrategyEntity>>
    @Query("SELECT * FROM local_strategy_configs WHERE id = :id LIMIT 1")
    suspend fun getStrategy(id: String): LocalStrategyEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRiskPolicy(policy: LocalRiskPolicyEntity)
    @Query("SELECT * FROM local_risk_policies WHERE id = 'default' LIMIT 1")
    fun observeRiskPolicy(): Flow<LocalRiskPolicyEntity?>
    @Query("SELECT * FROM local_risk_policies WHERE id = 'default' LIMIT 1")
    suspend fun getRiskPolicy(): LocalRiskPolicyEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUserProfile(profile: LocalUserProfileEntity)
    @Query("SELECT * FROM local_user_profile WHERE id = 'local' LIMIT 1")
    fun observeUserProfile(): Flow<LocalUserProfileEntity?>
    @Query("SELECT * FROM local_user_profile WHERE id = 'local' LIMIT 1")
    suspend fun getUserProfile(): LocalUserProfileEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailySummary(summary: LocalDailySummaryEntity)
    @Query("SELECT * FROM local_daily_pnl_summaries WHERE userId = :userId ORDER BY dateKey DESC LIMIT 30")
    fun observeDailySummaries(userId: String): Flow<List<LocalDailySummaryEntity>>
    @Query("SELECT * FROM local_daily_pnl_summaries WHERE dateKey = :dateKey LIMIT 1")
    suspend fun getDailySummary(dateKey: String): LocalDailySummaryEntity?

    @Query("SELECT COUNT(*) FROM sync_queue WHERE userId = :userId AND status = 'PENDING'")
    fun observePendingSyncCount(userId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE userId = :userId AND status = 'FAILED_PERMANENT'")
    fun observeFailedSyncCount(userId: String): Flow<Int>

    @Query(
        "SELECT * FROM sync_queue WHERE userId = :userId AND status = 'PENDING' AND nextRetryMillis <= :nowMillis " +
            "ORDER BY clientModifiedMillis ASC LIMIT :limit"
    )
    suspend fun getDueSyncItems(userId: String, nowMillis: Long, limit: Int = 20): List<SyncQueueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncItem(item: SyncQueueEntity)

    @Query("UPDATE sync_queue SET status = 'SYNCED', lastError = NULL WHERE queueId = :queueId")
    suspend fun markSyncItemDone(queueId: String)

    @Query(
        "UPDATE sync_queue SET retryCount = :retryCount, nextRetryMillis = :nextRetryMillis, " +
            "lastError = :lastError, status = :status WHERE queueId = :queueId"
    )
    suspend fun markSyncItemRetry(
        queueId: String,
        retryCount: Int,
        nextRetryMillis: Long,
        lastError: String,
        status: String
    )

    @Query("UPDATE sync_queue SET status = 'PENDING', retryCount = 0, nextRetryMillis = 0 WHERE userId = :userId AND status = 'FAILED_PERMANENT'")
    suspend fun resetFailedSyncItems(userId: String)

    @Query("DELETE FROM sync_queue WHERE status = 'SYNCED' AND clientModifiedMillis < :olderThanMillis")
    suspend fun pruneSyncedItems(olderThanMillis: Long)
}

@Database(
    entities = [CachedCandleEntity::class, LocalTradeCacheEntity::class, SyncQueueEntity::class, LocalStrategyEntity::class, LocalRiskPolicyEntity::class, LocalUserProfileEntity::class, LocalDailySummaryEntity::class],
    version = 3,
    exportSchema = false
)
abstract class TradingDatabase : RoomDatabase() {
    abstract fun tradingDao(): TradingDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_queue` (
                        `queueId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `documentId` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `clientModifiedMillis` INTEGER NOT NULL,
                        `retryCount` INTEGER NOT NULL,
                        `nextRetryMillis` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `status` TEXT NOT NULL,
                        PRIMARY KEY(`queueId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_queue_userId_status_nextRetryMillis` " +
                        "ON `sync_queue` (`userId`, `status`, `nextRetryMillis`)"
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS local_strategy_configs (id TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, name TEXT NOT NULL, instrument TEXT NOT NULL, timeframe TEXT NOT NULL, tradeAmount REAL NOT NULL, expirySeconds INTEGER NOT NULL, rsiPeriod INTEGER NOT NULL, rsiOversold INTEGER NOT NULL, rsiOverbought INTEGER NOT NULL, emaFast INTEGER NOT NULL, emaSlow INTEGER NOT NULL, macdFast INTEGER NOT NULL, macdSlow INTEGER NOT NULL, macdSignal INTEGER NOT NULL, bbPeriod INTEGER NOT NULL, bbStdDev REAL NOT NULL, minConfidence INTEGER NOT NULL, active INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_strategy_configs_userId ON local_strategy_configs(userId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS local_risk_policies (id TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, maxTradeAmount REAL NOT NULL, maxDailyLoss REAL NOT NULL, maxConsecutiveLosses INTEGER NOT NULL, maxDailyTrades INTEGER NOT NULL, maxPortfolioExposure REAL NOT NULL, cooldownMinutes INTEGER NOT NULL, emergencyStopped INTEGER NOT NULL, autoTradeEnabled INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS local_user_profile (id TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, displayName TEXT NOT NULL, executionMode TEXT NOT NULL, simulationBalance REAL NOT NULL, updatedAtMillis INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS local_daily_pnl_summaries (dateKey TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, totalTrades INTEGER NOT NULL, wins INTEGER NOT NULL, losses INTEGER NOT NULL, netPnl REAL NOT NULL, consecutiveLosses INTEGER NOT NULL, peakDrawdown REAL NOT NULL, updatedAtMillis INTEGER NOT NULL)")
                db.execSQL("INSERT OR IGNORE INTO local_user_profile (id,userId,displayName,executionMode,simulationBalance,updatedAtMillis) VALUES ('local','local','Trader','SIMULATION',10000.0,0)")
            }
        }

        @Volatile
        private var INSTANCE: TradingDatabase? = null

        fun getInstance(context: Context): TradingDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TradingDatabase::class.java,
                    "iq_trading_local_cache.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
