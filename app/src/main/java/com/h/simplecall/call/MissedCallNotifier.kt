package com.h.simplecall.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.h.simplecall.MainActivity
import com.h.simplecall.R

object MissedCallNotifier {

    private const val CHANNEL_ID = "missed_calls"
    private const val CHANNEL_NAME = "Cuộc gọi nhỡ"
    private var notifId = 1000

    fun init(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo cuộc gọi nhỡ"
                enableVibration(true)
            }
            ctx.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(ch)
        }
    }

    fun show(ctx: Context, number: String, name: String) {
        val display = name.ifEmpty { number }
        val callbackIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = android.net.Uri.fromParts("tel", number, null)
        }
        val pi = PendingIntent.getActivity(
            ctx, notifId, callbackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_missed)
            .setContentTitle(ctx.getString(R.string.missed_call))
            .setContentText(ctx.getString(R.string.missed_call_from, display))
            .setSubText(ctx.getString(R.string.tap_to_call_back))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(ctx.getColor(R.color.missed_red))
            .build()

        ctx.getSystemService(NotificationManager::class.java)
            ?.notify(notifId++, notif)
    }
}
