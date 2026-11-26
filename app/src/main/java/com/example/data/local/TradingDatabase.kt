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
    val syncedToCloud: Boolean = true
)

@Entity(tableName = "local_user_profiles")
data class LocalUserProfileEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val email: String? = null,
    val executionMode: String,
    val simulationBalance: Double,
    val lastSavedAtMillis: Long? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Entity(
    tableName = "local_strategies",
    indices = [Index(value = ["userId", "active"])]
)
data class LocalStrategyConfigEntity(
    @PrimaryKey val id: String,
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
    val active: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Entity(tableName = "local_risk_policies")
data class LocalRiskPolicyEntity(
    @PrimaryKey val userId: String,
    val maxTradeAmount: Double,
    val maxDailyLoss: Double,
    val maxConsecutiveLosses: Int,
    val maxDailyTrades: Int,
    val maxPortfolioExposure: Double,
    val cooldownMinutes: Int,
    val emergencyStopped: Boolean,
    val autoTradeEnabled: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Entity(
    tableName = "local_daily_summaries",
    indices = [Index(value = ["userId", "dateKey"], unique = true)]
)
data class LocalDailySummaryEntity(
    @PrimaryKey val summaryId: String,
    val userId: String,
    val dateKey: String,
    val totalTrades: Int,
    val wins: Int,
    val losses: Int,
    val netPnl: Double,
    val consecutiveLosses: Int,
    val peakDrawdown: Double,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Dao
interface TradingDao {
    // --- Candles ---
    @Query("SELECT * FROM cached_candles WHERE instrument = :instrument AND timeframe = :timeframe ORDER BY timestampMillis ASC LIMIT 120")
    fun observeCandles(instrument: String, timeframe: String): Flow<List<CachedCandleEntity>>

    @Query("SELECT COUNT(*) FROM cached_candles")
    fun observeCandleCount(): Flow<Int>

    @Query("DELETE FROM cached_candles WHERE instrument = :instrument AND timeframe = :timeframe")
    suspend fun clearCandles(instrument: String, timeframe: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandles(candles: List<CachedCandleEntity>)

    // --- User Profile ---
    @Query("SELECT * FROM local_user_profiles WHERE userId = :userId LIMIT 1")
    fun observeUserProfile(userId: String): Flow<LocalUserProfileEntity?>

    @Query("SELECT * FROM local_user_profiles WHERE userId = :userId LIMIT 1")
    suspend fun getUserProfileOnce(userId: String): LocalUserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUserProfile(profile: LocalUserProfileEntity)

    // --- Strategies ---
    @Query("SELECT * FROM local_strategies WHERE userId = :userId ORDER BY updatedAtMillis DESC")
    fun observeStrategies(userId: String): Flow<List<LocalStrategyConfigEntity>>

    @Query("SELECT * FROM local_strategies WHERE userId = :userId ORDER BY updatedAtMillis DESC")
    suspend fun getStrategiesOnce(userId: String): List<LocalStrategyConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStrategy(strategy: LocalStrategyConfigEntity)

    // --- Risk Policies ---
    @Query("SELECT * FROM local_risk_policies WHERE userId = :userId LIMIT 1")
    fun observeRiskPolicy(userId: String): Flow<LocalRiskPolicyEntity?>

    @Query("SELECT * FROM local_risk_policies WHERE userId = :userId LIMIT 1")
    suspend fun getRiskPolicyOnce(userId: String): LocalRiskPolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRiskPolicy(policy: LocalRiskPolicyEntity)

    // --- Trade Journal ---
    @Query("SELECT * FROM local_trade_logs WHERE userId = :userId ORDER BY createdAtMillis DESC LIMIT 500")
    fun observeLocalTrades(userId: String): Flow<List<LocalTradeCacheEntity>>

    @Query("SELECT COUNT(*) FROM local_trade_logs WHERE userId = :userId")
    fun observeTradeCount(userId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrade(trade: LocalTradeCacheEntity)

    @Query(
        "UPDATE local_trade_logs SET exitPrice = :exitPrice, status = :status, pnl = :pnl " +
            "WHERE tradeId = :tradeId"
    )
    suspend fun updateTradeSettlement(
        tradeId: String,
        exitPrice: Double,
        status: String,
        pnl: Double
    )

    // --- Daily P&L Summaries ---
    @Query("SELECT * FROM local_daily_summaries WHERE userId = :userId ORDER BY dateKey DESC LIMIT 60")
    fun observeDailySummaries(userId: String): Flow<List<LocalDailySummaryEntity>>

    @Query("SELECT * FROM local_daily_summaries WHERE summaryId = :summaryId LIMIT 1")
    suspend fun getDailySummaryOnce(summaryId: String): LocalDailySummaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailySummary(summary: LocalDailySummaryEntity)
}

@Database(
    entities = [
        CachedCandleEntity::class,
        LocalTradeCacheEntity::class,
        LocalUserProfileEntity::class,
        LocalStrategyConfigEntity::class,
        LocalRiskPolicyEntity::class,
        LocalDailySummaryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class TradingDatabase : RoomDatabase() {
    abstract fun tradingDao(): TradingDao

    companion object {
        const val DATABASE_NAME = "iq_trading_local_cache.db"

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
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `sync_queue`")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_user_profiles` (
                        `userId` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `email` TEXT,
                        `executionMode` TEXT NOT NULL,
                        `simulationBalance` REAL NOT NULL,
                        `lastSavedAtMillis` INTEGER,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`userId`)
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_strategies` (
                        `id` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `instrument` TEXT NOT NULL,
                        `timeframe` TEXT NOT NULL,
                        `tradeAmount` REAL NOT NULL,
                        `expirySeconds` INTEGER NOT NULL,
                        `rsiPeriod` INTEGER NOT NULL,
                        `rsiOversold` INTEGER NOT NULL,
                        `rsiOverbought` INTEGER NOT NULL,
                        `emaFast` INTEGER NOT NULL,
                        `emaSlow` INTEGER NOT NULL,
                        `macdFast` INTEGER NOT NULL,
                        `macdSlow` INTEGER NOT NULL,
                        `macdSignal` INTEGER NOT NULL,
                        `bbPeriod` INTEGER NOT NULL,
                        `bbStdDev` REAL NOT NULL,
                        `minConfidence` INTEGER NOT NULL,
                        `active` INTEGER NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_local_strategies_userId_active` " +
                        "ON `local_strategies` (`userId`, `active`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_risk_policies` (
                        `userId` TEXT NOT NULL,
                        `maxTradeAmount` REAL NOT NULL,
                        `maxDailyLoss` REAL NOT NULL,
                        `maxConsecutiveLosses` INTEGER NOT NULL,
                        `maxDailyTrades` INTEGER NOT NULL,
                        `maxPortfolioExposure` REAL NOT NULL,
                        `cooldownMinutes` INTEGER NOT NULL,
                        `emergencyStopped` INTEGER NOT NULL,
                        `autoTradeEnabled` INTEGER NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`userId`)
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_daily_summaries` (
                        `summaryId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `dateKey` TEXT NOT NULL,
                        `totalTrades` INTEGER NOT NULL,
                        `wins` INTEGER NOT NULL,
                        `losses` INTEGER NOT NULL,
                        `netPnl` REAL NOT NULL,
                        `consecutiveLosses` INTEGER NOT NULL,
                        `peakDrawdown` REAL NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`summaryId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_daily_summaries_userId_dateKey` " +
                        "ON `local_daily_summaries` (`userId`, `dateKey`)"
                )
            }
        }

        @Volatile
        private var INSTANCE: TradingDatabase? = null

        fun getInstance(context: Context): TradingDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TradingDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
