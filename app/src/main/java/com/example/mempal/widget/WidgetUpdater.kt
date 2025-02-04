package com.example.mempal.widget

import android.content.Context
import android.os.PowerManager
import androidx.work.*
import com.example.mempal.repository.SettingsRepository
import java.util.concurrent.TimeUnit

object WidgetUpdater {
    private const val WAKE_LOCK_TAG = "mempal:widget_update_wake_lock"
    private const val MIN_UPDATE_INTERVAL = 15L // Minimum update interval in minutes

    fun scheduleUpdates(context: Context) {
        val settingsRepository = SettingsRepository.getInstance(context)
        val updateInterval = settingsRepository.getUpdateFrequency().coerceAtLeast(MIN_UPDATE_INTERVAL)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = workDataOf(
            "wake_lock_tag" to WAKE_LOCK_TAG
        )

        // Cancel any existing work first
        WorkManager.getInstance(context).cancelUniqueWork(WidgetUpdateWorker.WORK_NAME)

        val updateRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
            updateInterval,
            TimeUnit.MINUTES,
            (updateInterval / 10).coerceAtLeast(1), // Flex interval is 10% of update interval
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WidgetUpdateWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE, // Always replace existing work
            updateRequest
        )
    }

    fun cancelUpdates(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WidgetUpdateWorker.WORK_NAME)
    }

    fun acquireWakeLock(context: Context): PowerManager.WakeLock? {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                acquire(2 * 60 * 1000L) // 2 minutes max - reduced from 5 minutes
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
} 