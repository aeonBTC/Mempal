package com.example.mempal.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.mempal.api.RateLimitGate
import kotlinx.coroutines.*

class WidgetUpdateWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private val tag = "WidgetUpdateWorker"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (RateLimitGate.isAnyBlocked()) {
            Log.d(tag, "Skipping widget update: server rate limit backoff active")
            return@withContext Result.success()
        }
        var wakeLock: PowerManager.WakeLock? = null
        try {
            // Initialize important services that would normally be initialized by the app
            initializeServices(appContext)
            
            // Check battery status for adaptive behavior
            val isUnderRestrictions = WidgetUpdater.checkUpdateRestrictions(appContext)
            
            // Acquire wake lock with adaptive timeout (only if not in power save mode)
            if (!isUnderRestrictions) {
                wakeLock = WidgetUpdater.acquireWakeLock(appContext)
            }
            
            // Check if we have widgets that need updating
            val appWidgetManager = AppWidgetManager.getInstance(appContext)
            val widgetsToUpdate = mutableListOf<Pair<Class<out AppWidgetProvider>, String>>()
            var totalWidgetInstances = 0
            
            // Check each widget type
            val widgetTypes = listOf(
                BlockHeightWidget::class.java to BlockHeightWidget.REFRESH_ACTION,
                MempoolSizeWidget::class.java to MempoolSizeWidget.REFRESH_ACTION,
                FeeRatesWidget::class.java to FeeRatesWidget.REFRESH_ACTION,
                CombinedStatsWidget::class.java to CombinedStatsWidget.REFRESH_ACTION
            )
            
            for ((widgetClass, action) in widgetTypes) {
                val widgetComponent = ComponentName(appContext, widgetClass)
                val widgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)
                if (widgetIds.isNotEmpty()) {
                    widgetsToUpdate.add(widgetClass to action)
                    totalWidgetInstances += widgetIds.size
                }
            }
            
            // If no widgets to update, just return success
            if (widgetsToUpdate.isEmpty()) {
                Log.d(tag, "No widgets found to update")
                return@withContext Result.success()
            }
            
            // Log update attempt for debugging
            Log.d(tag, "Updating ${widgetsToUpdate.size} widget types (${totalWidgetInstances} instances)")
            
            // Update all widgets in parallel for faster updates
            // PendingIntent.send() is non-blocking, so we can update all widgets simultaneously
            val completion = WidgetUpdateTracker.begin(totalWidgetInstances)
            try {
                coroutineScope {
                    widgetsToUpdate.forEachIndexed { index, (widgetClass, action) ->
                        launch {
                            updateWidget(appWidgetManager, widgetClass, action, index)
                        }
                    }
                }

                // Hold the wake lock until the providers report that their network
                // work is done, bounded so a lost broadcast cannot hang the worker.
                if (completion != null) {
                    withTimeoutOrNull(WIDGET_COMPLETION_TIMEOUT_MS) {
                        completion.await()
                    }
                }
            } finally {
                WidgetUpdateTracker.cancel()
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(tag, "Error updating widgets", e)
            e.printStackTrace()
            
            // Only retry if we have a network error, not for other exceptions
            if (e is java.net.UnknownHostException || 
                e is java.net.SocketTimeoutException ||
                e is java.io.IOException) {
                Result.retry()
            } else {
                Result.failure()
            }
        } finally {
            // Release wake lock
            WidgetUpdater.releaseWakeLock(wakeLock)
        }
    }

    private fun initializeServices(context: Context) {
        try {
            // This initialization is important for widget updates when app is killed
            // Initialize here in case the app's Application class hasn't initialized these
            WidgetUtils.ensureInitialized(context)
        } catch (e: Exception) {
            Log.e(tag, "Error initializing services: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun updateWidget(
        appWidgetManager: AppWidgetManager,
        widgetClass: Class<out AppWidgetProvider>,
        action: String,
        requestCode: Int
    ) {
        try {
            val widgetComponent = ComponentName(appContext, widgetClass)
            val widgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)
            
            if (widgetIds.isNotEmpty()) {
                val refreshIntent = Intent(appContext, widgetClass).apply {
                    this.action = action
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }

                PendingIntent.getBroadcast(
                    appContext,
                    requestCode,
                    refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ).send()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error updating widget: ${widgetClass.simpleName}", e)
            e.printStackTrace()
        }
    }

    companion object {
        const val WORK_NAME = "widget_update_work"
        private const val WIDGET_COMPLETION_TIMEOUT_MS = 20_000L
    }
}