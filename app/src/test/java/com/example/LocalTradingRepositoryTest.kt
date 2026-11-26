package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.TradingDatabase
import com.example.data.repository.LocalTradingRepository
import com.example.model.TradeRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalTradingRepositoryTest {

    @Test
    fun `local profile strategy risk and trade persist in Room without network`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, TradingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = LocalTradingRepository(db.tradingDao())
            repository.ensureLocalProfile("Local Trader")

            val profile = repository.observeUserProfile().first()
            assertEquals("Local Trader", profile?.displayName)
            assertEquals(10000.0, profile?.simulationBalance ?: 0.0, 0.0)

            val strategies = repository.observeStrategies(LocalTradingRepository.LOCAL_USER_ID).first()
            assertNotNull(strategies.firstOrNull { it.id == "default_ema_rsi" })

            val policy = repository.observeRiskPolicy().first()
            assertNotNull(policy)

            val trade = TradeRecord(
                id = "local_trade_test",
                instrument = "EUR/USD",
                direction = "CALL",
                amount = 10.0,
                entryPrice = 1.085
            )
            assertEquals("local_trade_test", repository.createTrade(trade).getOrThrow())
            val saved = repository.observeTrades(LocalTradingRepository.LOCAL_USER_ID).first()
            assertEquals(1, saved.size)
            assertEquals("local_trade_test", saved.first().id)
            assertEquals(10.0, saved.first().amount, 0.0)
        } finally {
            db.close()
        }
    }
}
