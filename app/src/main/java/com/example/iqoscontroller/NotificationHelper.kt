package com.example.iqoscontroller

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {

    private const val CHANNEL_ID = "iqos_usage_channel"
    private const val CHANNEL_NAME = "اعلان‌های مصرف ایکاس"
    private var lastNotifTime = 0L

    fun checkAndNotifyUsage(context: Context, todayPuffs: Int, baselineAvg: Float) {
        val prefs = context.getSharedPreferences("iqos_app_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notif_enabled", true)) return

        val now = System.currentTimeMillis()
        // Anti-spam: minimum 15 minutes between alerts
        if (now - lastNotifTime < 15 * 60 * 1000L) return

        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "گزارش وضعیت و هشدارهای مصرف ترا"
            }
            notifManager.createNotificationChannel(channel)
        }

        var title = "ایکاس: مصرف ثبت شد"
        var message = "یک ترا جدید ثبت شد. مجموع امروز: $todayPuffs ترا"

        if (baselineAvg > 0) {
            if (todayPuffs > baselineAvg * 1.3f) {
                title = "هشدار افزایش مصرف ایکاس"
                message = "امروز مصرف شما ($todayPuffs ترا) از میانگین معمول بیشتر شده است."
            } else if (todayPuffs <= baselineAvg * 0.7f && todayPuffs > 5) {
                title = "پیام تشویقی ایکاس"
                message = "آفرین! مصرف امروز شما کمتر از الگوی همیشگی بوده است."
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notifManager.notify(1001, notification)
        lastNotifTime = now
    }
}
