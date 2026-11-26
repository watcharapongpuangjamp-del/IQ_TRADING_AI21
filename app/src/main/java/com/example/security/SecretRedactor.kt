package com.example.security

/**
 * Redacts Bearer tokens, Telegram bot URL tokens, and arbitrary secrets before
 * error messages or diagnostics reach UI snackbars or system logs.
 */
object SecretRedactor {

    private val BEARER_REGEX = Regex("""(?i)\bBearer\s+[A-Za-z0-9._\-~+/=]{4,}""")
    private val TELEGRAM_BOT_REGEX = Regex("""\bbot\d{5,}:[A-Za-z0-9_-]{8,}""")
    private val TOKEN_KV_REGEX = Regex("""(?i)\b(api_token|bearer_token|bot_token|authorization)=([^&\s,;]+)""")

    fun redact(input: String?, vararg knownSecrets: String?): String {
        if (input.isNullOrBlank()) return ""
        var out: String = input
        for (secret in knownSecrets) {
            val trimmed = secret?.trim().orEmpty()
            if (trimmed.length >= 4) {
                out = out.replace(trimmed, "[REDACTED]")
            }
        }
        out = BEARER_REGEX.replace(out, "Bearer [REDACTED]")
        out = TELEGRAM_BOT_REGEX.replace(out, "bot[REDACTED]")
        out = TOKEN_KV_REGEX.replace(out) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
        return out
    }
}
