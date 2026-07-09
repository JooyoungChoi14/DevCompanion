package com.devcompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Build
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.devcompanion.ui.MainActivity

/**
 * Foreground service that keeps the agent loop running when the app
 * goes to the background. Shows a persistent notification so the user
 * knows an agent session is active.
 */
class AgentService : Service() {

    companion object {
        private const val TAG = "AgentService"
        const val CHANNEL_ID = "agent_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.devcompanion.ACTION_START_AGENT"
        const val ACTION_STOP = "com.devcompanion.ACTION_STOP_AGENT"
        const val ACTION_UPDATE = "com.devcompanion.ACTION_UPDATE_AGENT"
        const val EXTRA_TEXT = "com.devcompanion.EXTRA_TEXT"
        const val BROADCAST_AGENT_STOPPED = "com.devcompanion.AGENT_STOPPED"

        /** Update the foreground notification text from outside the service. */
        fun updateNotification(context: android.content.Context, text: String) {
            val intent = Intent(context, AgentService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TEXT, text)
            }
            context.startService(intent)
        }
    }

    private var isStopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (!isStopping) {
                    isStopping = true
                    Log.d(TAG, "Stop requested — removing foreground, scheduling stopSelf")
                    // Notify ViewModel to stop the agent loop before stopping service
                    val stopBroadcast = Intent(BROADCAST_AGENT_STOPPED)
                    LocalBroadcastManager.getInstance(this).sendBroadcast(stopBroadcast)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    android.os.Handler(mainLooper).postDelayed({
                        stopSelf(startId)
                    }, 500L)
                }
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_NOT_STICKY
                if (!isStopping) {
                    val notification = buildNotification(text)
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, notification)
                }
                return START_NOT_STICKY
            }
        }

        if (isStopping) {
            // A stop is already in flight; don't re-start foreground.
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notification = buildNotification("Agent is running…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isStopping = false
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // App was swiped away from recents — stop the service immediately.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent Session",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when an agent session is active"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        openIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AgentService::class.java)
        stopIntent.action = ACTION_STOP
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DevCompanion")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
}