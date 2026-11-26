package com.example.engine

import com.example.model.BackendHealthState
import com.example.model.DataQualitySummary
import com.example.model.ExecutionMode
import com.example.model.IntegrationSettings
import com.example.model.LiveReadinessGateItem
import com.example.model.LiveReadinessReport
import com.example.model.RiskPolicy

/**
 * Evaluates the 5 mandatory Master Prompt gates before LIVE trading may be unlocked.
 * Defaults strictly to SIMULATION.
 */
object LiveReadinessEvaluator {

    fun evaluateLocal(
        backendHealth: BackendHealthState,
        riskPolicy: RiskPolicy,
        settings: IntegrationSettings,
        dataQuality: DataQualitySummary,
        unresolvedOrdersCount: Int,
        currentMode: ExecutionMode,
        ownerConfirmed: Boolean = false
    ): LiveReadinessReport {
        val hasLiveCapability = "LIVE_ORDERS" in backendHealth.supportedCapabilities
        val brokerVerified = backendHealth.connected && backendHealth.liveAuthorized && hasLiveCapability
        val brokerDetail = when {
            !backendHealth.connected -> "FastAPI backend / live broker connector ยังไม่เชื่อมต่อ (Simulation default)."
            !backendHealth.liveAuthorized -> "Connector '${backendHealth.connectorName}' ยังไม่อนุญาต LIVE execution (ALLOW_LIVE_TRADING=false)."
            !hasLiveCapability -> "Connector ปัจจุบันไม่รองรับความสามารถ LIVE_ORDERS"
            else -> "Broker '${backendHealth.connectorName}' พร้อมและได้รับสิทธิ์ LIVE_ORDERS"
        }

        val riskValid = !riskPolicy.emergencyStopped &&
            riskPolicy.maxTradeAmount > 0.0 &&
            riskPolicy.maxDailyLoss > 0.0 &&
            riskPolicy.maxPortfolioExposure >= riskPolicy.maxTradeAmount &&
            riskPolicy.maxDailyTrades > 0
        val riskDetail = when {
            riskPolicy.emergencyStopped -> "Emergency Stop เปิดใช้งานอยู่ — ต้องปลดล็อกก่อน"
            !riskValid -> "ค่าเพดาน Risk Policy ไม่ถูกต้อง (ต้อง > 0 และ Exposure >= Stake)"
            else -> "Risk caps พร้อม: สูงสุด $${riskPolicy.maxTradeAmount.toInt()}/ไม้, ขาดทุนสูงสุด $${riskPolicy.maxDailyLoss.toInt()}/วัน"
        }

        val isHttpsBackend = settings.backendBaseUrl.trim().startsWith("https://", ignoreCase = true)
        val authValid = settings.hasBackendToken && (isHttpsBackend || settings.backendBaseUrl.contains("10.0.2.2") || settings.backendBaseUrl.contains("localhost"))
        val authDetail = when {
            !settings.hasBackendToken -> "ยังไม่ตั้งค่า Backend Bearer Token ใน Encrypted Vault"
            !authValid -> "แนะนำใช้ HTTPS URL สำหรับการส่งคำสั่ง LIVE"
            else -> "Keystore AES Token & API Authentication พร้อมใช้งาน"
        }

        val execPassed = (unresolvedOrdersCount == 0) && dataQuality.valid
        val execDetail = when {
            unresolvedOrdersCount > 0 -> "มีออเดอร์ค้าง $unresolvedOrdersCount รายการ (PENDING/UNKNOWN) ต้อง Reconcile ก่อน"
            !dataQuality.valid -> "คุณภาพข้อมูลแท่งเทียนไม่ผ่าน: ${dataQuality.reason}"
            else -> "ไม่มีออเดอร์ค้าง และข้อมูลตลาดผ่านเกณฑ์คุณภาพ"
        }

        val ownerDetail = if (ownerConfirmed) {
            "เจ้าของบัญชีรับทราบความเสี่ยงและยืนยันเปิด LIVE แล้ว"
        } else {
            "ต้องกดยืนยัน 2 ขั้นตอนด้วยตนเองเมื่อเปิดใช้งานโหมด LIVE"
        }

        val gates = listOf(
            LiveReadinessGateItem(
                gateId = "broker_verified",
                stepNumber = 1,
                title = "1. Broker Integration Verified",
                passed = brokerVerified,
                detail = brokerDetail
            ),
            LiveReadinessGateItem(
                gateId = "risk_config_valid",
                stepNumber = 2,
                title = "2. Risk Configuration Valid",
                passed = riskValid,
                detail = riskDetail
            ),
            LiveReadinessGateItem(
                gateId = "auth_and_vault_valid",
                stepNumber = 3,
                title = "3. Vault & API Auth Valid",
                passed = authValid,
                detail = authDetail
            ),
            LiveReadinessGateItem(
                gateId = "execution_checks_passed",
                stepNumber = 4,
                title = "4. Execution & Reconciliation Clear",
                passed = execPassed,
                detail = execDetail
            ),
            LiveReadinessGateItem(
                gateId = "owner_confirmed",
                stepNumber = 5,
                title = "5. Explicit User Confirmation",
                passed = ownerConfirmed,
                detail = ownerDetail
            )
        )

        val prereqs = brokerVerified && riskValid && authValid && execPassed
        return LiveReadinessReport(
            prerequisitesReady = prereqs,
            overallReady = prereqs && ownerConfirmed,
            currentMode = currentMode,
            gates = gates,
            blockingReasons = gates.filter { !it.passed }.map { it.detail },
            evaluatedAtMillis = System.currentTimeMillis()
        )
    }
}
