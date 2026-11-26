package com.example.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.IntegrationSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.integrationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "iq_trading_integration_prefs"
)

/**
 * Hardware-backed AES-256-GCM credential vault using AndroidKeyStore.
 * Never persists broker passwords on Android; encrypts only Telegram Bot Token
 * and optional Python FastAPI Backend Bearer Token.
 */
class SecureCredentialVault(context: Context) {

    private val appContext = context.applicationContext
    private val encryptedPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private object Keys {
        val BACKEND_BASE_URL = stringPreferencesKey("backend_base_url")
        val BACKEND_WS_URL = stringPreferencesKey("backend_ws_url")
        val TELEGRAM_CHAT_ID = stringPreferencesKey("telegram_chat_id")
        val NOTIFY_SIGNAL = booleanPreferencesKey("notify_signal")
        val NOTIFY_TRADE = booleanPreferencesKey("notify_trade")
        val NOTIFY_RISK = booleanPreferencesKey("notify_risk")
        val NOTIFY_DAILY = booleanPreferencesKey("notify_daily")
        val NOTIFY_LOCAL = booleanPreferencesKey("notify_local")
        val HAS_BACKEND_TOKEN = booleanPreferencesKey("has_backend_token")
        val HAS_TELEGRAM_TOKEN = booleanPreferencesKey("has_telegram_token")
        val CLOUD_SYNC_ENABLED = booleanPreferencesKey("cloud_sync_enabled")
    }

    val settingsFlow: Flow<IntegrationSettings> = appContext.integrationDataStore.data.map { prefs ->
        IntegrationSettings(
            backendBaseUrl = prefs[Keys.BACKEND_BASE_URL].orEmpty(),
            backendWsUrl = prefs[Keys.BACKEND_WS_URL].orEmpty(),
            telegramChatId = prefs[Keys.TELEGRAM_CHAT_ID].orEmpty(),
            notifyOnSignal = prefs[Keys.NOTIFY_SIGNAL] ?: true,
            notifyOnTradeResult = prefs[Keys.NOTIFY_TRADE] ?: true,
            notifyOnRiskStop = prefs[Keys.NOTIFY_RISK] ?: true,
            notifyDailySummary = prefs[Keys.NOTIFY_DAILY] ?: true,
            enableLocalNotifications = prefs[Keys.NOTIFY_LOCAL] ?: true,
            cloudSyncEnabled = prefs[Keys.CLOUD_SYNC_ENABLED] ?: false,
            hasBackendToken = prefs[Keys.HAS_BACKEND_TOKEN] ?: !getBackendToken().isNullOrBlank(),
            hasTelegramToken = prefs[Keys.HAS_TELEGRAM_TOKEN] ?: !getTelegramBotToken().isNullOrBlank()
        )
    }

    suspend fun saveIntegrationSettings(
        backendBaseUrl: String,
        backendWsUrl: String,
        telegramChatId: String,
        notifyOnSignal: Boolean,
        notifyOnTradeResult: Boolean,
        notifyOnRiskStop: Boolean,
        notifyDailySummary: Boolean,
        newBackendToken: String?,
        newTelegramBotToken: String?,
        enableLocalNotifications: Boolean = true
    ) {
        if (newBackendToken != null) {
            if (newBackendToken.isBlank()) removeEncryptedSecret(KEY_BACKEND_TOKEN)
            else storeEncryptedSecret(KEY_BACKEND_TOKEN, newBackendToken.trim())
        }
        if (newTelegramBotToken != null) {
            if (newTelegramBotToken.isBlank()) removeEncryptedSecret(KEY_TELEGRAM_TOKEN)
            else storeEncryptedSecret(KEY_TELEGRAM_TOKEN, newTelegramBotToken.trim())
        }

        appContext.integrationDataStore.edit { prefs ->
            prefs[Keys.BACKEND_BASE_URL] = backendBaseUrl.trim()
            prefs[Keys.BACKEND_WS_URL] = backendWsUrl.trim()
            prefs[Keys.TELEGRAM_CHAT_ID] = telegramChatId.trim()
            prefs[Keys.NOTIFY_SIGNAL] = notifyOnSignal
            prefs[Keys.NOTIFY_TRADE] = notifyOnTradeResult
            prefs[Keys.NOTIFY_RISK] = notifyOnRiskStop
            prefs[Keys.NOTIFY_DAILY] = notifyDailySummary
            prefs[Keys.NOTIFY_LOCAL] = enableLocalNotifications
            prefs[Keys.HAS_BACKEND_TOKEN] = !getBackendToken().isNullOrBlank()
            prefs[Keys.HAS_TELEGRAM_TOKEN] = !getTelegramBotToken().isNullOrBlank()
        }
    }

    suspend fun isCloudSyncEnabled(): Boolean =
        appContext.integrationDataStore.data.first()[Keys.CLOUD_SYNC_ENABLED] ?: false

    suspend fun setCloudSyncEnabled(enabled: Boolean) {
        appContext.integrationDataStore.edit { prefs -> prefs[Keys.CLOUD_SYNC_ENABLED] = enabled }
    }

    fun getBackendToken(): String? = readEncryptedSecret(KEY_BACKEND_TOKEN)

    fun getTelegramBotToken(): String? = readEncryptedSecret(KEY_TELEGRAM_TOKEN)

    private fun getOrCreateAesKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun storeEncryptedSecret(prefKey: String, plaintext: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        encryptedPrefs.edit().putString(prefKey, encoded).apply()
    }

    private fun readEncryptedSecret(prefKey: String): String? {
        val raw = encryptedPrefs.getString(prefKey, null) ?: return null
        val parts = raw.split(":")
        if (parts.size != 2) return null
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateAesKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun removeEncryptedSecret(prefKey: String) {
        encryptedPrefs.edit().remove(prefKey).apply()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "iq_trading_vault_aes_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFS_NAME = "iq_trading_secure_vault"
        const val KEY_BACKEND_TOKEN = "enc_backend_token"
        const val KEY_TELEGRAM_TOKEN = "enc_telegram_bot_token"
    }
}
