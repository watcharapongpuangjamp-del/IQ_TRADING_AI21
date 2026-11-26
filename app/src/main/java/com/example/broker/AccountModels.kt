package com.example.broker

enum class AccountType { PRACTICE, REAL }
enum class ConnectionState { DISCONNECTED, CONNECTING, AUTH_REQUIRED, CONNECTED, RECONNECTING, ERROR }
enum class BrokerCapability { ACCOUNT_READ, BALANCE_READ, CANDLE_READ, PRACTICE_ORDER, REAL_ORDER, ORDER_STATUS, RECONCILIATION }

data class AccountSelection(val accountType: AccountType, val selectedAtMillis: Long = System.currentTimeMillis())

data class AccountSnapshot(
    val brokerAccountId: String,
    val accountType: AccountType,
    val currency: String,
    val balance: Double,
    val availableBalance: Double,
    val serverTimestampMillis: Long,
    val capabilities: Set<BrokerCapability>,
    val isDemo: Boolean
) {
    init {
        require(brokerAccountId.isNotBlank()) { "Broker account ID is required." }
        require(currency.isNotBlank()) { "Account currency is required." }
        require(balance.isFinite() && balance >= 0.0) { "Invalid account balance." }
        require(availableBalance.isFinite() && availableBalance >= 0.0) { "Invalid available balance." }
        require(serverTimestampMillis > 0L) { "A broker timestamp is required." }
        require(isDemo == (accountType == AccountType.PRACTICE)) {
            "Broker demo flag does not match the reported account type."
        }
    }
    val maskedAccountId: String
        get() = if (brokerAccountId.length <= 4) "••••" else "••••" + brokerAccountId.takeLast(4)
}

data class AccountSession(val requestedType: AccountType, val state: ConnectionState,
    val snapshot: AccountSnapshot? = null, val message: String? = null)

/** Reference to a secret held by Android Keystore-backed storage; never a password/token. */
data class BrokerCredentials(val secretReference: String) {
    init { require(secretReference.isNotBlank()) }
}

data class OrderIntent(val intentId: String, val accountType: AccountType, val amount: Double,
    val createdAtMillis: Long = System.currentTimeMillis()) {
    init { require(intentId.isNotBlank()); require(amount.isFinite() && amount > 0.0) }
}
