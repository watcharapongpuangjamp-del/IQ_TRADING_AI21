package com.example.data.remote

import com.example.security.SecretRedactor
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class TelegramMessageRequest(
    val chat_id: String,
    val text: String,
    val parse_mode: String = "HTML",
    val disable_web_page_preview: Boolean = true
)

@JsonClass(generateAdapter = true)
data class TelegramApiResponse(
    val ok: Boolean = false,
    val description: String? = null
)

interface TelegramBotApi {
    @POST("bot{token}/sendMessage")
    suspend fun sendMessage(
        @Path("token") token: String,
        @Body body: TelegramMessageRequest
    ): Response<TelegramApiResponse>
}

class TelegramNotifier {

    private val api: TelegramBotApi by lazy {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        // Never attach HttpLoggingInterceptor body logger to avoid leaking bot token in URL path
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://api.telegram.org/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TelegramBotApi::class.java)
    }

    suspend fun sendAlert(
        botToken: String?,
        chatId: String,
        title: String,
        bodyLines: List<String>
    ): Result<String> {
        if (botToken.isNullOrBlank() || chatId.isBlank()) {
            return Result.failure(IllegalStateException("กรุณาตั้งค่า Telegram Bot Token และ Chat ID ก่อน"))
        }
        val cleanToken = botToken.trim()
        val safeTitle = escapeHtml(title)
        val safeBody = bodyLines.joinToString("\n") { escapeHtml(it) }
        val message = "<b>$safeTitle</b>\n$safeBody"

        return try {
            val response = api.sendMessage(
                token = cleanToken,
                body = TelegramMessageRequest(
                    chat_id = chatId.trim(),
                    text = message
                )
            )
            if (response.isSuccessful && response.body()?.ok == true) {
                Result.success("ส่งแจ้งเตือนเข้า Telegram สำเร็จ")
            } else {
                val errDesc = SecretRedactor.redact(
                    response.body()?.description ?: "HTTP ${response.code()}",
                    cleanToken
                )
                Result.failure(IllegalStateException("Telegram API ตอบกลับ: $errDesc"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val safeError = SecretRedactor.redact(e.localizedMessage, cleanToken).ifBlank { "Request error" }
            Result.failure(IllegalStateException("เชื่อมต่อ Telegram ไม่สำเร็จ: $safeError"))
        }
    }

    private fun escapeHtml(input: String): String = input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
