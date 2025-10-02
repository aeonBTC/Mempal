package com.example.mempal.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.example.mempal.R
import com.example.mempal.api.WidgetNetworkClient
import kotlinx.coroutines.*

class FeeRatesWidget : AppWidgetProvider() {
    companion object {
        const val REFRESH_ACTION = "com.example.mempal.REFRESH_FEE_RATES_WIDGET"
        private var widgetScope: CoroutineScope? = null
        private var activeJobs = mutableMapOf<Int, Job>()
        private const val TAG = "FeeRatesWidget"
    }

    private fun getOrCreateScope(): CoroutineScope {
        return widgetScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also { widgetScope = it }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        getOrCreateScope() // Initialize scope when widget is enabled
        
        // Ensure services initialized
        WidgetUtils.ensureInitialized(context)
        
        // Register network connectivity monitor
        NetworkConnectivityReceiver.registerNetworkCallback(context)
        
        // Schedule updates
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
        // Ensure initialization on ANY event to the widget
        WidgetUtils.ensureInitialized(context)
        
        super.onReceive(context, intent)
        
        // First see if this is a system event handled by the common handler
        if (WidgetEventHandler.handleSystemEvent(context, intent, FeeRatesWidget::class.java, REFRESH_ACTION)) {
            return
        }
        
        // Otherwise handle widget-specific REFRESH_ACTION
        if (intent.action == REFRESH_ACTION) {
            if (WidgetUtils.isDoubleTap()) {
                // Launch app on double tap
                val launchIntent = WidgetUtils.getLaunchAppIntent(context)
                launchIntent.send()
            } else {
                // Single tap - refresh only this widget
                Log.d(TAG, "Refresh action received - updating widget")
                
                // Check for network availability before trying to update
                if (!WidgetNetworkClient.isNetworkAvailable(context)) {
                    Log.d(TAG, "Network unavailable, resetting tap state")
                    // No network, reset tap state immediately to prevent getting stuck
                    WidgetUtils.resetTapState()
                    
                    // Update widget with error state
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val thisWidget = ComponentName(context, FeeRatesWidget::class.java)
                    val widgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                    
                    // Just update the first widget to show error message
                    if (widgetIds.isNotEmpty()) {
                        val views = RemoteViews(context.packageName, R.layout.fee_rates_widget)
                        setErrorState(views)
                        appWidgetManager.updateAppWidget(widgetIds[0], views)
                    }
                    
                    return
                }
                
                // Network is available, proceed with update
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
        // Ensure services are initialized
        WidgetUtils.ensureInitialized(context)
        
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
            // Include flags to make it work better when app is killed
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context, 
            appWidgetId, // Use widget ID as request code for uniqueness
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE // Use FLAG_MUTABLE instead of FLAG_IMMUTABLE
        )

        // Cancel any existing job for this widget
        activeJobs[appWidgetId]?.cancel()
        
        // Set loading state first
        setLoadingState(views)
        // Set click handler immediately after creating views
        views.setOnClickPendingIntent(R.id.widget_layout, refreshPendingIntent)
        appWidgetManager.updateAppWidget(appWidgetId, views)

        // Create a separate mutable state for views that can be updated by multiple coroutines
        val sharedViews = views
        var viewsUpdated = false

        // Start new job
        activeJobs[appWidgetId] = getOrCreateScope().launch {
            var viewsPreparedForData = false
            try {
                Log.d(TAG, "Starting network request for widget update for ID: $appWidgetId")
                val mempoolApi = WidgetNetworkClient.getMempoolApi(context)
                
                val feeRatesDeferred = async(SupervisorJob() + coroutineContext) { 
                    try { mempoolApi.getFeeRates() } catch (e: Exception) { Log.e(TAG, "Fee rates request failed for $appWidgetId: ${e.message}"); null }
                }
                
                val response = feeRatesDeferred.await()

                if (response != null && response.isSuccessful) {
                    response.body()?.let { feeRates ->
                        sharedViews.setTextViewText(R.id.priority_fee, "${feeRates.fastestFee}")
                        sharedViews.setTextViewText(R.id.standard_fee, "${feeRates.halfHourFee}")
                        sharedViews.setTextViewText(R.id.economy_fee, "${feeRates.hourFee}")
                        viewsPreparedForData = true
                        
                        // Update widget with data immediately when we have it
                        if (!viewsUpdated) {
                            appWidgetManager.updateAppWidget(appWidgetId, sharedViews)
                            viewsUpdated = true
                            Log.d(TAG, "Widget $appWidgetId UI updated with new data")
                        }
                        
                        Log.d(TAG, "Data prepared for widget ID: $appWidgetId")
                    } ?: run {
                        Log.w(TAG, "Fee rates response body was null for ID: $appWidgetId")
                    }
                } else {
                    Log.w(TAG, "Fee rates response unsuccessful or null for ID: $appWidgetId. Code: ${response?.code()}")
                }

                if (!viewsPreparedForData) {
                    setErrorState(sharedViews)
                    Log.d(TAG, "Error state set for widget ID: $appWidgetId after data fetch attempt")
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Job for widget $appWidgetId was cancelled during try block.")
                
                // Don't throw the exception if we already updated the UI
                if (!viewsUpdated && viewsPreparedForData) {
                    // Final attempt to update UI before exiting
                    appWidgetManager.updateAppWidget(appWidgetId, sharedViews)
                    Log.d(TAG, "Managed to update widget $appWidgetId UI despite job cancellation")
                    viewsUpdated = true
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exception during widget update for ID: $appWidgetId", e)
                setErrorState(sharedViews)
                Log.d(TAG, "Error state set for widget ID: $appWidgetId due to exception")
            } finally {
                val job = coroutineContext[Job]
                if (job?.isCancelled == false || !viewsUpdated) {
                    appWidgetManager.updateAppWidget(appWidgetId, sharedViews)
                    Log.d(TAG, "Final update in finally for widget $appWidgetId. Data: $viewsPreparedForData")
                } else {
                    Log.d(TAG, "Job for widget $appWidgetId was cancelled. UI was already updated in finally.")
                }
                activeJobs.remove(appWidgetId)
                WidgetUtils.resetTapState()
            }
        }
    }

    private fun setLoadingState(views: RemoteViews) {
        views.setTextViewText(R.id.priority_fee, "...")
        views.setTextViewText(R.id.standard_fee, "...")
        views.setTextViewText(R.id.economy_fee, "...")
    }

    private fun setErrorState(views: RemoteViews) {
        views.setTextViewText(R.id.priority_fee, "?")
        views.setTextViewText(R.id.standard_fee, "?")
        views.setTextViewText(R.id.economy_fee, "?")
    }
} 