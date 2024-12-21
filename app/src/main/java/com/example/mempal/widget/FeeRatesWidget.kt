package com.example.mempal.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.mempal.R
import com.example.mempal.api.WidgetNetworkClient
import kotlinx.coroutines.*

class FeeRatesWidget : AppWidgetProvider() {
    companion object {
        const val REFRESH_ACTION = "com.example.mempal.REFRESH_FEE_RATES_WIDGET"
        private var widgetScope: CoroutineScope? = null
        private var activeJobs = mutableMapOf<Int, Job>()
    }

    private fun getOrCreateScope(): CoroutineScope {
        return widgetScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also { widgetScope = it }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        getOrCreateScope() // Initialize scope when widget is enabled
        WidgetUpdater.scheduleUpdates(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Only cancel updates if no other widgets are active
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val blockHeightWidget = ComponentName(context, BlockHeightWidget::class.java)
        val combinedStatsWidget = ComponentName(context, CombinedStatsWidget::class.java)
        val mempoolSizeWidget = ComponentName(context, MempoolSizeWidget::class.java)
        
        if (appWidgetManager.getAppWidgetIds(blockHeightWidget).isEmpty() &&
            appWidgetManager.getAppWidgetIds(combinedStatsWidget).isEmpty() &&
            appWidgetManager.getAppWidgetIds(mempoolSizeWidget).isEmpty()) {
            WidgetUpdater.cancelUpdates(context)
            // Cancel any ongoing coroutines
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
            widgetScope?.cancel()
            widgetScope = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == REFRESH_ACTION) {
            if (WidgetUtils.isDoubleTap()) {
                // Launch app on double tap
                val launchIntent = WidgetUtils.getLaunchAppIntent(context)
                launchIntent.send()
            } else {
                // Single tap - refresh widget
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisWidget = ComponentName(context, FeeRatesWidget::class.java)
                onUpdate(context, appWidgetManager, appWidgetManager.getAppWidgetIds(thisWidget))
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Update each widget
        appWidgetIds.forEach { appWidgetId ->
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.fee_rates_widget)

        // Create refresh intent
        val refreshIntent = Intent(context, FeeRatesWidget::class.java).apply {
            action = REFRESH_ACTION
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context, 0, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_layout, refreshPendingIntent)

        // Cancel any existing job for this widget
        activeJobs[appWidgetId]?.cancel()
        
        // Set loading state first
        setLoadingState(views)
        appWidgetManager.updateAppWidget(appWidgetId, views)

        // Start new job
        activeJobs[appWidgetId] = getOrCreateScope().launch {
            try {
                val mempoolApi = WidgetNetworkClient.getMempoolApi(context)
                var hasAnyData = false
                
                // Launch API call immediately
                val feeRatesDeferred = async { mempoolApi.getFeeRates() }
                
                // Process response
                try {
                    val response = feeRatesDeferred.await()
                    if (response.isSuccessful) {
                        response.body()?.let { feeRates ->
                            views.setTextViewText(R.id.priority_fee, "${feeRates.fastestFee}")
                            views.setTextViewText(R.id.standard_fee, "${feeRates.halfHourFee}")
                            views.setTextViewText(R.id.economy_fee, "${feeRates.hourFee}")
                            hasAnyData = true
                            appWidgetManager.updateAppWidget(appWidgetId, views)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (!hasAnyData) {
                    setErrorState(views, "No data")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                setErrorState(views, "Network error")
            } finally {
                appWidgetManager.updateAppWidget(appWidgetId, views)
                activeJobs.remove(appWidgetId)
            }
        }
    }

    private fun setLoadingState(views: RemoteViews) {
        views.setTextViewText(R.id.priority_fee, "...")
        views.setTextViewText(R.id.standard_fee, "...")
        views.setTextViewText(R.id.economy_fee, "...")
    }

    private fun setErrorState(views: RemoteViews, error: String) {
        views.setTextViewText(R.id.priority_fee, "!")
        views.setTextViewText(R.id.standard_fee, "!")
        views.setTextViewText(R.id.economy_fee, "($error)")
    }
} 