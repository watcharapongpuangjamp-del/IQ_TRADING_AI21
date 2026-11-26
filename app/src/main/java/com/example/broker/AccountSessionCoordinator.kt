package com.example.broker

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes account changes, validates broker-reported account type, and never falls back. */
class AccountSessionCoordinator(private val connector: BrokerConnector) {
    private val mutex = Mutex()
    private var current: AccountSession? = null

    suspend fun currentSession(): AccountSession? = mutex.withLock { current }

    suspend fun connect(selection: AccountSelection, credentials: BrokerCredentials): AccountSession =
        mutex.withLock {
            val existing = current
            if (existing?.state == ConnectionState.CONNECTED &&
                existing.requestedType == selection.accountType
            ) {
                return@withLock try {
                    validateAndStore(selection.accountType, connector.refreshAccount())
                } catch (error: Exception) {
                    current = AccountSession(selection.accountType, ConnectionState.ERROR,
                        message = error.message ?: "Account refresh failed.")
                    current!!
                }
            }

            val pending = connector.unresolvedOrderCount(existing?.requestedType ?: selection.accountType)
            if (pending > 0) {
                return@withLock AccountSession(
                    requestedType = selection.accountType,
                    state = existing?.state ?: ConnectionState.ERROR,
                    snapshot = existing?.snapshot,
                    message = "Account switch blocked: unresolved orders must be reconciled first."
                )
            }

            current = AccountSession(selection.accountType, ConnectionState.CONNECTING)
            try {
                connector.disconnect()
                val snapshot = connector.connect(selection.accountType, credentials)
                validateAndStore(selection.accountType, snapshot)
            } catch (error: Exception) {
                current = AccountSession(selection.accountType, ConnectionState.ERROR,
                    message = error.message ?: "Broker connection failed.")
                current!!
            }
        }

    suspend fun disconnect() = mutex.withLock {
        connector.disconnect()
        current = current?.copy(state = ConnectionState.DISCONNECTED, snapshot = null)
    }

    suspend fun refresh(): AccountSession = mutex.withLock {
        val active = current
        if (active == null || active.state != ConnectionState.CONNECTED) {
            return@withLock active ?: AccountSession(AccountType.PRACTICE,
                ConnectionState.DISCONNECTED, message = "No selected account.")
        }
        try {
            validateAndStore(active.requestedType, connector.refreshAccount())
        } catch (error: Exception) {
            current = active.copy(state = ConnectionState.ERROR, snapshot = null,
                message = error.message ?: "Account refresh failed.")
            current!!
        }
    }

    private fun validateAndStore(requestedType: AccountType, snapshot: AccountSnapshot): AccountSession {
        if (snapshot.accountType != requestedType) {
            current = AccountSession(requestedType, ConnectionState.ERROR, message =
                "Account type mismatch: requested ${requestedType} but broker reported ${snapshot.accountType}.")
            throw AccountTypeMismatchException(requestedType, snapshot.accountType)
        }
        return AccountSession(requestedType, ConnectionState.CONNECTED, snapshot).also { current = it }
    }
}

class AccountTypeMismatchException(val requested: AccountType, val reported: AccountType) :
    IllegalStateException("Requested ${requested} but broker reported ${reported}.")
