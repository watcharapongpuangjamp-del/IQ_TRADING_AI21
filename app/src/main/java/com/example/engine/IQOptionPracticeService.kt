package com.example.engine

import android.content.Context
import com.example.security.SecureCredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Application service for the embedded IQAir Practice read-only connection. */
class IQOptionPracticeService(context: Context) {
    private val vault = SecureCredentialVault(context.applicationContext)
    private val bridge = PythonEngineBridge(context.applicationContext)

    suspend fun connect(
        email: String,
        password: String,
        rememberCredentials: Boolean = false
    ): PracticeConnectionResult = withContext(Dispatchers.IO) {
        require(email.isNotBlank()) { "Email is required." }
        require(password.isNotEmpty()) { "Password is required." }

        if (rememberCredentials) vault.saveBrokerCredentials(email, password)
        else vault.clearBrokerCredentials()

        parseResult(bridge.connectPracticeReadOnly(email, password))
    }

    suspend fun connectWithSavedCredentials(): PracticeConnectionResult = withContext(Dispatchers.IO) {
        val email = vault.getBrokerEmail()
            ?: return@withContext PracticeConnectionResult(false, "AUTH_REQUIRED", error = "No saved broker email.")
        val password = vault.getBrokerPassword()
            ?: return@withContext PracticeConnectionResult(false, "AUTH_REQUIRED", error = "No saved broker password.")
        parseResult(bridge.connectPracticeReadOnly(email, password))
    }

    suspend fun forceReconnect(
        email: String,
        password: String,
        rememberCredentials: Boolean = false
    ): PracticeConnectionResult = withContext(Dispatchers.IO) {
        require(email.isNotBlank()) { "Email is required." }
        require(password.isNotEmpty()) { "Password is required." }
        if (rememberCredentials) vault.saveBrokerCredentials(email, password)
        else vault.clearBrokerCredentials()

        val reset = JSONObject(bridge.forceReconnectIqOption())
        if (!reset.optBoolean("ok", false)) {
            return@withContext PracticeConnectionResult(
                ok = false,
                state = reset.optString("state", "ERROR"),
                error = reset.optString("error", "Could not reset this device session.")
            )
        }
        parseResult(bridge.connectPracticeReadOnly(email, password))
    }

    suspend fun disconnect(): PracticeConnectionResult = withContext(Dispatchers.IO) {
        parseResult(bridge.disconnectIqOption())
    }

    fun clearSavedCredentials() = vault.clearBrokerCredentials()

    private fun parseResult(raw: String): PracticeConnectionResult {
        val json = JSONObject(raw)
        return PracticeConnectionResult(
            ok = json.optBoolean("ok", false),
            state = json.optString("state", "ERROR"),
            accountType = json.optString("accountType").takeIf { it.isNotBlank() },
            balance = if (json.has("balance") && !json.isNull("balance")) json.optDouble("balance") else null,
            currency = json.optString("currency").takeIf { it.isNotBlank() },
            accountId = json.optString("accountId").takeIf { it.isNotBlank() },
            error = json.optString("error").takeIf { it.isNotBlank() },
            ordersEnabled = json.optBoolean("ordersEnabled", false)
        )
    }
}

data class PracticeConnectionResult(
    val ok: Boolean,
    val state: String,
    val accountType: String? = null,
    val balance: Double? = null,
    val currency: String? = null,
    val accountId: String? = null,
    val error: String? = null,
    val ordersEnabled: Boolean = false
)
