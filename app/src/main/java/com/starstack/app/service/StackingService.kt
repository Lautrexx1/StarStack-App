package com.starstack.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.starstack.app.processing.StackingEngine

/**
 * Foreground Service for running stacking processes in the background without interruptions.
 * Fully compatible with Android 14 (API 34) and Android 15 (API 35) foreground service types.
 */
class StackingService : Service() {

    private val binder = StackingBinder()
    private val stackingEngine = StackingEngine()

    companion object {
        private const val CHANNEL_ID = "stacking_service_channel"
        private const val NOTIFICATION_ID = 2026
    }

    inner class StackingBinder : Binder() {
        fun getService(): StackingService = this@StackingService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        stackingEngine.initialize()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start foreground with dataSync type for compatibility with targetSdk 35
        val notification = createNotification("Initializing astrophotography stacking engine...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        stackingEngine.release()
        super.onDestroy()
    }

    fun getEngine(): StackingEngine = stackingEngine

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("StarStack Processing")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // Compass placeholder icon
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "StarStack Processing Engine"
            val descriptionText = "Monitors background image processing and stacking tasks"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
