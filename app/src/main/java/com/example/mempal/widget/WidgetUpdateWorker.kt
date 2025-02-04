package com.example.mempal.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.*

class WidgetUpdateWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        var wakeLock: PowerManager.WakeLock? = null
        try {
            // Acquire wake lock
            wakeLock = WidgetUpdater.acquireWakeLock(appContext)

            val appWidgetManager = AppWidgetManager.getInstance(appContext)
            val updateDelay = 500L // Default delay between widget updates

            // Update each type of widget with a delay between them
            updateWidget(appWidgetManager, BlockHeightWidget::class.java, BlockHeightWidget.REFRESH_ACTION, 0)
            delay(updateDelay)
            updateWidget(appWidgetManager, MempoolSizeWidget::class.java, MempoolSizeWidget.REFRESH_ACTION, 1)
            delay(updateDelay)
            updateWidget(appWidgetManager, FeeRatesWidget::class.java, FeeRatesWidget.REFRESH_ACTION, 2)
            delay(updateDelay)
            updateWidget(appWidgetManager, CombinedStatsWidget::class.java, CombinedStatsWidget.REFRESH_ACTION, 3)

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        } finally {
            // Release wake lock
            WidgetUpdater.releaseWakeLock(wakeLock)
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
            e.printStackTrace()
        }
    }

    companion object {
        const val WORK_NAME = "widget_update_work"
    }
}