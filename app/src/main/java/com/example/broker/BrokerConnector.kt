package com.example.broker

/** Broker boundary. Implementations must report account type from broker data, not echo UI selection. */
interface BrokerConnector {
    val connectorId: String
    val isBrokerAuthorized: Boolean
    suspend fun connect(requestedType: AccountType, credentials: BrokerCredentials): AccountSnapshot
    suspend fun disconnect()
    suspend fun refreshAccount(): AccountSnapshot
    suspend fun unresolvedOrderCount(accountType: AccountType): Int
}
