package com.example.mempal.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class WidgetUpdateWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        try {
            val appWidgetManager = AppWidgetManager.getInstance(appContext)

            // Update BlockHeightWidget
            val blockHeightWidget = ComponentName(appContext, BlockHeightWidget::class.java)
            val blockHeightWidgetIds = appWidgetManager.getAppWidgetIds(blockHeightWidget)
            if (blockHeightWidgetIds.isNotEmpty()) {
                val blockHeightIntent = Intent(appContext, BlockHeightWidget::class.java).apply {
                    action = BlockHeightWidget.REFRESH_ACTION
                }
                appContext.sendBroadcast(blockHeightIntent)
            }

            // Update CombinedStatsWidget
            val combinedStatsWidget = ComponentName(appContext, CombinedStatsWidget::class.java)
            val combinedStatsWidgetIds = appWidgetManager.getAppWidgetIds(combinedStatsWidget)
            if (combinedStatsWidgetIds.isNotEmpty()) {
                val combinedStatsIntent = Intent(appContext, CombinedStatsWidget::class.java).apply {
                    action = CombinedStatsWidget.REFRESH_ACTION
                }
                appContext.sendBroadcast(combinedStatsIntent)
            }

            // Update MempoolSizeWidget
            val mempoolSizeWidget = ComponentName(appContext, MempoolSizeWidget::class.java)
            val mempoolSizeWidgetIds = appWidgetManager.getAppWidgetIds(mempoolSizeWidget)
            if (mempoolSizeWidgetIds.isNotEmpty()) {
                val mempoolSizeIntent = Intent(appContext, MempoolSizeWidget::class.java).apply {
                    action = MempoolSizeWidget.REFRESH_ACTION
                }
                appContext.sendBroadcast(mempoolSizeIntent)
            }

            // Update FeeRatesWidget
            val feeRatesWidget = ComponentName(appContext, FeeRatesWidget::class.java)
            val feeRatesWidgetIds = appWidgetManager.getAppWidgetIds(feeRatesWidget)
            if (feeRatesWidgetIds.isNotEmpty()) {
                val feeRatesIntent = Intent(appContext, FeeRatesWidget::class.java).apply {
                    action = FeeRatesWidget.REFRESH_ACTION
                }
                appContext.sendBroadcast(feeRatesIntent)
            }

            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "widget_update_work"
    }
}