package com.example.plohoystream.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Keeps the process foregrounded (camera|microphone) while streaming. */
class StreamForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "stream"
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(channelId, "Streaming", NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("PlohoyStream")
            .setContentText("Live")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .build()
        startForeground(
            1, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        return START_NOT_STICKY
    }

    companion object {
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, StreamForegroundService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, StreamForegroundService::class.java))
    }
}
