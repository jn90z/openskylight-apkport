package com.jn90z.openskylight

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class CompanionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Companion web app", NotificationManager.IMPORTANCE_LOW)
        )
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(8420, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OpenSkyLight companion")
            .setContentText("Sharing the family calendar on your local network")
            .setContentIntent(launch)
            .setOngoing(true)
            .build())
        Backend.get(this)
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "companion"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CompanionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CompanionService::class.java))
        }
    }
}
