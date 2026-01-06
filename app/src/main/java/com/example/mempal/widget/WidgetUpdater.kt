package com.example.mempal.widget

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.work.*
import com.example.mempal.repository.SettingsRepository
import java.util.concurrent.TimeUnit

object WidgetUpdater {
    private const val WAKE_LOCK_TAG = "mempal:widget_update_wake_lock"
    private const val MIN_UPDATE_INTERVAL = 15L // Minimum update interval in minutes
    private const val ONE_TIME_WORK_NAME = "widget_one_time_update_work"
    private const val DELAYED_WORK_NAME = "widget_delayed_update_work"
    private const val MIN_UPDATE_THRESHOLD = 5 * 60 * 1000L // 5 minutes in milliseconds
    private const val TAG = "WidgetUpdater"
    
    // Track the last time widgets were updated
    private var lastUpdateTime = 0L

    // Check if enough time has passed since last update to allow a system-event triggered update
    fun shouldUpdate(context: Context? = null): Boolean {
        val currentTime = SystemClock.elapsedRealtime()
        val baseThreshold = MIN_UPDATE_THRESHOLD
        
        // Adapt threshold based on battery state if context is available
        val threshold = if (context != null) {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val isCharging = batteryManager.isCharging
            
            // If charging or high battery, allow more frequent updates
            when {
                isCharging -> baseThreshold / 2  // Half the threshold if charging
                batteryPct > 80 -> baseThreshold * 3 / 4  // 75% of threshold if battery > 80%
                batteryPct < 20 -> baseThreshold * 2  // Double threshold if battery < 20%
                else -> baseThreshold
            }
        } else {
            baseThreshold
        }
        
        return (currentTime - lastUpdateTime) > threshold
    }

    fun scheduleUpdates(context: Context) {
        val settingsRepository = SettingsRepository.getInstance(context)
        val updateInterval = settingsRepository.getUpdateFrequency().coerceAtLeast(MIN_UPDATE_INTERVAL)

        // Cancel any existing work first
        WorkManager.getInstance(context).cancelUniqueWork(WidgetUpdateWorker.WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(DELAYED_WORK_NAME)

        // Create standard network constraints
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = workDataOf(
            "wake_lock_tag" to WAKE_LOCK_TAG
        )

        // 1. Schedule periodic updates with flexible interval
        val updateRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
            updateInterval,
            TimeUnit.MINUTES,
            (updateInterval / 5).coerceAtLeast(5), // Increased flexibility window
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
            ExistingPeriodicWorkPolicy.UPDATE,
            updateRequest
        )
        
        // 2a. Schedule an immediate expedited work without delay
        val expeditedRequest = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
            
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            expeditedRequest
        )
        
        // 2b. Also schedule a regular (non-expedited) delayed job
        try {
            val delayTime = (updateInterval / 2).coerceAtMost(30)
            Log.d(TAG, "Scheduling delayed update for $delayTime minutes from now")
            
            val delayedRequest = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .setInitialDelay(delayTime, TimeUnit.MINUTES)
                .build()
                
            WorkManager.getInstance(context).enqueueUniqueWork(
                DELAYED_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                delayedRequest
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling delayed update", e)
        }
        
        // Update the last update time
        lastUpdateTime = SystemClock.elapsedRealtime()
    }

    fun requestImmediateUpdate(context: Context, force: Boolean = false) {
        // Only request an update if it's been at least 1 minute since the last update
        // unless force is true
        val currentTime = SystemClock.elapsedRealtime()
        if (force || (currentTime - lastUpdateTime) > 60000) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
                
            val expeditedRequest = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
                
            WorkManager.getInstance(context).enqueueUniqueWork(
                "widget_immediate_update",
                ExistingWorkPolicy.REPLACE,
                expeditedRequest
            )
            
            lastUpdateTime = currentTime
        }
    }
    
    // Check if updates are likely to be restricted based on battery status
    fun checkUpdateRestrictions(context: Context): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val isCharging = batteryManager.isCharging
        
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isPowerSaveMode = powerManager.isPowerSaveMode
        
        // If device is charging or not in power save mode, updates should work fine
        return !isCharging && isPowerSaveMode
    }

    fun cancelUpdates(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WidgetUpdateWorker.WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(DELAYED_WORK_NAME)
    }

    fun acquireWakeLock(context: Context): PowerManager.WakeLock? {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                acquire(1 * 60 * 1000L) // Reduced to 1 minute max to be more battery-friendly
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