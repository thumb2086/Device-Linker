package com.dlinker.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "balance_alerts"

    fun sendBalanceNotification(context: Context, amount: Double, total: Double) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "餘額變動提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "當帳戶收到代幣時發送通知"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // 使用系統內建圖示確保相容性
            .setContentTitle("代幣入帳通知")
            .setContentText("您收到了 ${String.format("%.2f", amount)} 個代幣！目前餘額：${String.format("%.2f", total)}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
