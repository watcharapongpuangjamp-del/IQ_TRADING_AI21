package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
}

@Database(
    entities = [CachedCandleEntity::class, LocalTradeCacheEntity::class],
    version = 1,
    exportSchema = false
)
abstract class TradingDatabase : RoomDatabase() {
    abstract fun tradingDao(): TradingDao

    companion object {
        @Volatile
        private var INSTANCE: TradingDatabase? = null

        fun getInstance(context: Context): TradingDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TradingDatabase::class.java,
                    "iq_trading_local_cache.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
