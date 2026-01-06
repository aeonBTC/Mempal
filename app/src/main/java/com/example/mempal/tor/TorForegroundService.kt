package com.example.mempal.tor

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.mempal.R
import com.example.mempal.service.NotificationService

class TorForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val wakelockTag = "mempal:torServiceWakelock"

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NotificationService.NOTIFICATION_ID, createSilentNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationService.NOTIFICATION_ID, createSilentNotification())
        }
        // Renew wakelock to ensure it stays active for long-running service
        renewWakeLock()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun createSilentNotification(): Notification {
        // Create a silent notification that won't be visible to the user
        return NotificationCompat.Builder(this, NotificationService.CHANNEL_ID)
            .setContentTitle("Mempal")
            .setContentText("Monitoring Bitcoin Network")
            .setSmallIcon(R.drawable.ic_cube)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakelockTag).apply {
                // Use 4 hour timeout as a safety net - the service should release it in onDestroy()
                // but this ensures the OS will clean it up if something goes wrong (e.g., crash)
                // For long-running sessions, the wakelock will be renewed in onStartCommand if needed
                acquire(4 * 60 * 60 * 1000L)
            }
        }
    }

    private fun renewWakeLock() {
        // Renew wakelock to ensure it stays active for long-running service
        // If wakelock expired or doesn't exist, acquire a new one
        // This is called in onStartCommand which runs periodically for START_STICKY services
        try {
            if (wakeLock?.isHeld != true) {
                // Release old wakelock if it exists but isn't held (expired)
                wakeLock?.let {
                    try {
                        if (it.isHeld) it.release()
                    } catch (_: Exception) {
                        // Ignore - wakelock might already be released
                    }
                }
                // Acquire a fresh wakelock with new timeout
                acquireWakeLock()
            }
        } catch (_: Exception) {
            // If renewal fails, try to acquire anyway
            acquireWakeLock()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
} 