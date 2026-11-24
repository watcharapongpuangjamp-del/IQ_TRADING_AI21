package com.example.ui.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

class TradingNotificationHelper(context: Context) {

    private val appContext = context.applicationContext

    init {
        ensureChannelsCreated()
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        }
    }

    fun ensureChannelsCreated() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val signalChannel = NotificationChannel(
                CHANNEL_SIGNALS,
                "AI Trading Signals & Trades",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "แจ้งเตือนสัญญาณ AI (CALL/PUT) และผลปิดออเดอร์"
            }

            val riskChannel = NotificationChannel(
                CHANNEL_RISK,
                "Risk Guard & Emergency Stop",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "แจ้งเตือนเมื่อ Risk Guard ล็อกการเทรดหรือเปิด Emergency Stop"
            }

            manager.createNotificationChannel(signalChannel)
            manager.createNotificationChannel(riskChannel)
        }
    }

    fun notifySignal(title: String, body: String): Boolean {
        return postNotification(
            channelId = CHANNEL_SIGNALS,
            notificationId = 1001,
            title = title,
            body = body,
            priority = NotificationCompat.PRIORITY_DEFAULT
        )
    }

    fun notifyTradeResult(title: String, body: String): Boolean {
        return postNotification(
            channelId = CHANNEL_SIGNALS,
            notificationId = 1002,
            title = title,
            body = body,
            priority = NotificationCompat.PRIORITY_DEFAULT
        )
    }

    fun notifyRiskAlert(title: String, body: String): Boolean {
        return postNotification(
            channelId = CHANNEL_RISK,
            notificationId = 2001,
            title = title,
            body = body,
            priority = NotificationCompat.PRIORITY_HIGH
        )
    }

    fun notifyTest(): Boolean {
        return postNotification(
            channelId = CHANNEL_SIGNALS,
            notificationId = 1099,
            title = "IQ Trading AI • Local Notification พร้อมใช้งาน",
            body = "ระบบแจ้งเตือนสัญญาณเทรดและความเสี่ยงบนอุปกรณ์ทำงานปกติ",
            priority = NotificationCompat.PRIORITY_DEFAULT
        )
    }

    private fun postNotification(
        channelId: String,
        notificationId: Int,
        title: String,
        body: String,
        priority: Int
    ): Boolean {
        if (!hasNotificationPermission()) return false
        ensureChannelsCreated()

        val launchIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            NotificationManagerCompat.from(appContext).notify(notificationId, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        const val CHANNEL_SIGNALS = "iq_trading_signals"
        const val CHANNEL_RISK = "iq_trading_risk_alerts"
    }
}
