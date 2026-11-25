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

    @Query("UPDATE local_trade_logs SET syncedToCloud = 1 WHERE tradeId = :tradeId")
    suspend fun markSynced(tradeId: String)

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
    entities = [CachedCandleEntity::class, LocalTradeCacheEntity::class, SyncQueueEntity::class],
    version = 2,
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

        @Volatile
        private var INSTANCE: TradingDatabase? = null

        fun getInstance(context: Context): TradingDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TradingDatabase::class.java,
                    "iq_trading_local_cache.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
