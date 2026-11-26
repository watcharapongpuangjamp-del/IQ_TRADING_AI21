package com.example.broker

data class OrderAuthorizationRequest(
    val accountSession: AccountSession?,
    val amount: Double,
    val nowMillis: Long = System.currentTimeMillis(),
    val maxSnapshotAgeMillis: Long = 15_000L,
    val userConfirmedRealTrading: Boolean = false,
    val realTradingFeatureEnabled: Boolean = false,
    val connectorAuthorizedForReal: Boolean = false
)
data class OrderAuthorizationResult(val allowed: Boolean, val reason: String)

/** Kotlin-owned hard gate; strategy output cannot bypass these checks. */
object OrderAuthorizationGate {
    fun evaluate(request: OrderAuthorizationRequest): OrderAuthorizationResult {
        val session = request.accountSession ?: return deny("No account session.")
        if (session.state != ConnectionState.CONNECTED) return deny("Account is not connected.")
        val snapshot = session.snapshot ?: return deny("Broker account snapshot is missing.")
        if (snapshot.accountType != session.requestedType) return deny("Account type mismatch.")
        if (!request.amount.isFinite() || request.amount <= 0.0) return deny("Invalid order amount.")
        if (request.nowMillis < snapshot.serverTimestampMillis ||
            request.nowMillis - snapshot.serverTimestampMillis > request.maxSnapshotAgeMillis
        ) return deny("Account balance snapshot is stale.")
        if (request.amount > snapshot.availableBalance) return deny("Insufficient available balance.")

        return when (session.requestedType) {
            AccountType.PRACTICE -> if (BrokerCapability.PRACTICE_ORDER in snapshot.capabilities)
                allow("Practice order passed account-level checks.")
            else deny("Practice order capability is unavailable.")
            AccountType.REAL -> when {
                !request.realTradingFeatureEnabled -> deny("Real trading is locked.")
                !request.userConfirmedRealTrading -> deny("Explicit Real trading confirmation is required.")
                !request.connectorAuthorizedForReal -> deny("Broker connector is not authorized for Real trading.")
                BrokerCapability.REAL_ORDER !in snapshot.capabilities -> deny("Real order capability is unavailable.")
                else -> allow("Real order passed account-level checks.")
            }
        }
    }
    private fun allow(reason: String) = OrderAuthorizationResult(true, reason)
    private fun deny(reason: String) = OrderAuthorizationResult(false, reason)
}
