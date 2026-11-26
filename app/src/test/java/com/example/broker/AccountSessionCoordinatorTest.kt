package com.example.broker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AccountSessionCoordinatorTest {
    private class FakeConnector : BrokerConnector {
        override val connectorId = "fake"
        override var isBrokerAuthorized = false
        var actualType = AccountType.PRACTICE
        var pending = 0
        override suspend fun connect(requestedType: AccountType, credentials: BrokerCredentials) = snapshot(actualType)
        override suspend fun disconnect() = Unit
        override suspend fun refreshAccount() = snapshot(actualType)
        override suspend fun unresolvedOrderCount(accountType: AccountType) = pending
    }

    @Test fun requestedAccountMustMatchBrokerReportedType() = runBlocking {
        val coordinator = AccountSessionCoordinator(FakeConnector().apply { actualType = AccountType.REAL })
        val session = coordinator.connect(AccountSelection(AccountType.PRACTICE), BrokerCredentials("keystore://practice"))
        assertEquals(ConnectionState.ERROR, session.state)
        assertNull(session.snapshot)
        assertTrue(session.message!!.contains("Account type mismatch"))
    }

    @Test fun switchingIsBlockedWhilePreviousAccountHasUnresolvedOrders() = runBlocking {
        val connector = FakeConnector()
        val coordinator = AccountSessionCoordinator(connector)
        assertEquals(ConnectionState.CONNECTED,
            coordinator.connect(AccountSelection(AccountType.PRACTICE), BrokerCredentials("keystore://p")).state)
        connector.pending = 1
        val result = coordinator.connect(AccountSelection(AccountType.REAL), BrokerCredentials("keystore://r"))
        assertTrue(result.message!!.contains("unresolved orders"))
        assertEquals(AccountType.REAL, result.requestedType)
    }

    @Test fun realAccountRemainsLockedByDefault() {
        val session = AccountSession(AccountType.REAL, ConnectionState.CONNECTED, snapshot(AccountType.REAL))
        val result = OrderAuthorizationGate.evaluate(OrderAuthorizationRequest(session, 10.0))
        assertFalse(result.allowed)
        assertEquals("Real trading is locked.", result.reason)
    }

    @Test fun staleSnapshotBlocksOrder() {
        val session = AccountSession(AccountType.PRACTICE, ConnectionState.CONNECTED,
            snapshot(AccountType.PRACTICE, timestamp = 1_000L))
        val result = OrderAuthorizationGate.evaluate(OrderAuthorizationRequest(session, 10.0, nowMillis = 100_000L))
        assertFalse(result.allowed)
        assertEquals("Account balance snapshot is stale.", result.reason)
    }

    @Test fun practiceOrderRequiresPracticeCapability() {
        val noCapability = snapshot(AccountType.PRACTICE).copy(capabilities = emptySet())
        val session = AccountSession(AccountType.PRACTICE, ConnectionState.CONNECTED, noCapability)
        val result = OrderAuthorizationGate.evaluate(OrderAuthorizationRequest(session, 10.0))
        assertFalse(result.allowed)
        assertEquals("Practice order capability is unavailable.", result.reason)
    }

    private fun snapshot(type: AccountType, timestamp: Long = System.currentTimeMillis()) = AccountSnapshot(
        brokerAccountId = "account-1234", accountType = type, currency = "USD",
        balance = 1000.0, availableBalance = 500.0, serverTimestampMillis = timestamp,
        capabilities = setOf(BrokerCapability.ACCOUNT_READ, BrokerCapability.BALANCE_READ,
            BrokerCapability.PRACTICE_ORDER), isDemo = type == AccountType.PRACTICE
    )
}
