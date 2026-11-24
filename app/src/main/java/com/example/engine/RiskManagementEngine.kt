package com.example.engine

import com.example.model.DailyPnLSummary
import com.example.model.ExecutionMode
import com.example.model.RiskPolicy
import com.example.model.RiskValidationResult
import com.example.model.TradeDirection

/**
 * Independent Risk Management Engine.
 * Enforces mandatory pre-trade validation before any order submission.
 * No AI signal or strategy component may override these restrictions.
 */
object RiskManagementEngine {

    fun validateOrder(
        policy: RiskPolicy,
        todaySummary: DailyPnLSummary?,
        openExposure: Double,
        requestedAmount: Double,
        direction: TradeDirection,
        mode: ExecutionMode,
        backendReadyForLive: Boolean,
        lastLossTimestampMillis: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): RiskValidationResult {
        val totalTradesToday = todaySummary?.totalTrades ?: 0
        val netPnlToday = todaySummary?.netPnl ?: 0.0
        val consecutiveLosses = todaySummary?.consecutiveLosses ?: 0
        val dailyLossSoFar = if (netPnlToday < 0.0) -netPnlToday else 0.0
        val remainingTrades = (policy.maxDailyTrades - totalTradesToday).coerceAtLeast(0)
        val remainingLossBudget = (policy.maxDailyLoss - dailyLossSoFar).coerceAtLeast(0.0)

        if (policy.emergencyStopped) {
            return RiskValidationResult(
                allowed = false,
                reason = "Emergency Stop ทำงานอยู่ — ระงับคำสั่งซื้อขายทั้งหมด",
                remainingDailyTrades = remainingTrades,
                remainingLossBudget = remainingLossBudget
            )
        }

        if (direction == TradeDirection.NO_TRADE) {
            return RiskValidationResult(
                allowed = false,
                reason = "AI ประเมินเป็น NO TRADE จึงไม่ออกคำสั่ง",
                remainingDailyTrades = remainingTrades,
                remainingLossBudget = remainingLossBudget
            )
        }

        if (mode == ExecutionMode.LIVE && !backendReadyForLive) {
            return RiskValidationResult(
                allowed = false,
                reason = "โหมด LIVE ถูกปิดล็อกจนกว่า FastAPI Connector จะผ่านการตรวจสอบ",
                remainingDailyTrades = remainingTrades,
                remainingLossBudget = remainingLossBudget
            )
        }

        if (requestedAmount <= 0.0 || requestedAmount > policy.maxTradeAmount) {
            return RiskValidationResult(
                allowed = false,
                reason = "ขนาดไม้ ($${format2(requestedAmount)}) เกินวงเงินสูงสุดต่อไม้ ($${format2(policy.maxTradeAmount)})",
                remainingDailyTrades = remainingTrades,
                remainingLossBudget = remainingLossBudget
            )
        }

        if (dailyLossSoFar >= policy.maxDailyLoss) {
            return RiskValidationResult(
                allowed = false,
                reason = "ถึงจุดตัดขาดทุนรายวันสูงสุดแล้ว ($${format2(dailyLossSoFar)} / $${format2(policy.maxDailyLoss)})",
                remainingDailyTrades = remainingTrades,
                remainingLossBudget = 0.0
            )
        }

        if (consecutiveLosses >= policy.maxConsecutiveLosses) {
            return RiskValidationResult(
                allowed = false,
                reason = "แพ้ติดต่อกันครบ ${policy.maxConsecutiveLosses} ครั้ง — ล็อกการเทรดอัตโนมัติ",
                remainingDailyTrades = remainingTrades,
                remainingLossBudget = remainingLossBudget
            )
        }

        if (totalTradesToday >= policy.maxDailyTrades) {
            return RiskValidationResult(
                allowed = false,
                reason = "ครบจำนวนไม้สูงสุดต่อวัน (${policy.maxDailyTrades} ไม้) แล้ว",
                remainingDailyTrades = 0,
                remainingLossBudget = remainingLossBudget
            )
        }

        if (openExposure + requestedAmount > policy.maxPortfolioExposure) {
            return RiskValidationResult(
                allowed = false,
                reason = "ความเสี่ยงพอร์ตรวม ($${format2(openExposure + requestedAmount)}) เกินลิมิต ($${format2(policy.maxPortfolioExposure)})",
                remainingDailyTrades = remainingTrades,
                remainingLossBudget = remainingLossBudget
            )
        }

        if (policy.cooldownMinutes > 0 && lastLossTimestampMillis != null && consecutiveLosses > 0) {
            val cooldownMillis = policy.cooldownMinutes * 60_000L
            val elapsed = nowMillis - lastLossTimestampMillis
            if (elapsed in 0 until cooldownMillis) {
                val remainSec = ((cooldownMillis - elapsed) / 1000L).coerceAtLeast(1L)
                return RiskValidationResult(
                    allowed = false,
                    reason = "อยู่ในช่วง Cooldown หลังขาดทุน เหลืออีก ${remainSec} วินาที",
                    remainingDailyTrades = remainingTrades,
                    remainingLossBudget = remainingLossBudget
                )
            }
        }

        return RiskValidationResult(
            allowed = true,
            reason = "ผ่านการตรวจสอบ Risk Guard ทั้งหมด",
            remainingDailyTrades = remainingTrades,
            remainingLossBudget = remainingLossBudget
        )
    }

    private fun format2(value: Double): String = String.format("%.2f", value)
}
