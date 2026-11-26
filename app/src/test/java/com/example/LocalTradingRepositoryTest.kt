package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.TradingDatabase
import com.example.data.repository.LocalTradingRepository
import com.example.model.DailyPnLSummary
import com.example.model.ExecutionMode
import com.example.model.TradeDirection
import com.example.model.TradeRecord
import com.example.model.TradeStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalTradingRepositoryTest {

    @Test
    fun `profile strategy risk trades and pnl persist only in local Room database`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, TradingDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val userId = "local-test-user"
            val repository = LocalTradingRepository(database.tradingDao())

            repository.ensureUserBootstrap(userId, "Local Test Trader").getOrThrow()

            val profile = repository.observeUserProfile(userId).first()
            assertNotNull(profile)
            assertEquals("Local Test Trader", profile?.displayName)
            assertEquals(10_000.0, profile?.simulationBalance ?: 0.0, 0.0)
            assertEquals(ExecutionMode.SIMULATION.name, profile?.executionMode)

            val strategies = repository.observeStrategies(userId).first()
            assertTrue(strategies.any { it.id == "default_ema_rsi_$userId" })

            val riskPolicy = repository.observeRiskPolicy(userId).first()
            assertNotNull(riskPolicy)
            assertFalse(riskPolicy?.emergencyStopped ?: true)

            val trade = TradeRecord(
                id = "local_trade_test_001",
                userId = userId,
                instrument = "EUR/USD",
                direction = TradeDirection.CALL.name,
                amount = 10.0,
                entryPrice = 1.085,
                status = TradeStatus.OPEN.name
            )
            val tradeId = repository.createTrade(trade).getOrThrow()
            assertEquals(trade.id, tradeId)

            repository.settleTrade(
                tradeId = tradeId,
                exitPrice = 1.086,
                status = TradeStatus.WON,
                pnl = 8.5
            ).getOrThrow()

            val savedTrades = repository.observeTrades(userId).first()
            assertEquals(1, savedTrades.size)
            assertEquals(tradeId, savedTrades.single().id)
            assertEquals(TradeStatus.WON.name, savedTrades.single().status)
            assertEquals(8.5, savedTrades.single().pnl, 0.0)

            val today = repository.todayDateKey()
            repository.upsertDailySummary(
                DailyPnLSummary(
                    id = today,
                    userId = userId,
                    dateKey = today,
                    totalTrades = 1,
                    wins = 1,
                    losses = 0,
                    netPnl = 8.5
                )
            ).getOrThrow()
            assertEquals(1, repository.observeDailySummaries(userId).first().size)

            repository.updateUserBalanceAndMode(
                userId = userId,
                newBalance = 10_008.5,
                mode = ExecutionMode.SIMULATION
            ).getOrThrow()
            assertEquals(10_008.5, repository.observeUserProfile(userId).first()?.simulationBalance ?: 0.0, 0.0)

            val tradeColumns = mutableSetOf<String>()
            database.openHelper.readableDatabase
                .query("PRAGMA table_info(local_trade_logs)")
                .use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) tradeColumns += cursor.getString(nameIndex)
                }
            assertFalse("Local SQL schema must not retain the legacy cloud sync flag", tradeColumns.contains("syncedToCloud"))
        } finally {
            database.close()
        }
    }
    @Test
    fun `migration 4 to 5 preserves trade rows and removes legacy cloud marker`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "local_only_migration_test.db"
        context.deleteDatabase(databaseName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE local_trade_logs (
                                tradeId TEXT NOT NULL PRIMARY KEY,
                                userId TEXT NOT NULL,
                                instrument TEXT NOT NULL,
                                timeframe TEXT NOT NULL,
                                direction TEXT NOT NULL,
                                mode TEXT NOT NULL,
                                amount REAL NOT NULL,
                                payoutRate REAL NOT NULL,
                                entryPrice REAL NOT NULL,
                                exitPrice REAL NOT NULL,
                                status TEXT NOT NULL,
                                pnl REAL NOT NULL,
                                strategyName TEXT NOT NULL,
                                marketRegime TEXT NOT NULL,
                                confidenceScore INTEGER NOT NULL,
                                calibratedProbability REAL NOT NULL,
                                explanation TEXT NOT NULL,
                                createdAtMillis INTEGER NOT NULL,
                                syncedToCloud INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            INSERT INTO local_trade_logs VALUES (
                                'legacy_trade_001', 'local-user', 'EUR/USD', '1m', 'CALL',
                                'SIMULATION', 10.0, 0.85, 1.085, 1.086, 'WON', 8.5,
                                'EMA-RSI', 'TRENDING_UP', 75, 0.72, 'legacy local row',
                                1700000000000, 1
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )

        try {
            val db = helper.writableDatabase
            TradingDatabase.MIGRATION_4_5.migrate(db)

            db.query("SELECT tradeId, amount, status FROM local_trade_logs").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy_trade_001", cursor.getString(cursor.getColumnIndexOrThrow("tradeId")))
                assertEquals(10.0, cursor.getDouble(cursor.getColumnIndexOrThrow("amount")), 0.0)
                assertEquals("WON", cursor.getString(cursor.getColumnIndexOrThrow("status")))
            }

            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(local_trade_logs)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertFalse(columns.contains("syncedToCloud"))
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

}
