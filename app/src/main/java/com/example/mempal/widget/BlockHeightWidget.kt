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
import java.util.*

class BlockHeightWidget : AppWidgetProvider() {
    companion object {
        const val REFRESH_ACTION = "com.example.mempal.REFRESH_BLOCK_HEIGHT_WIDGET"
        private var widgetScope: CoroutineScope? = null
        private var activeJobs = mutableMapOf<Int, Job>()
        private const val TAG = "BlockHeightWidget"
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
        val mempoolSizeWidget = ComponentName(context, MempoolSizeWidget::class.java)
        val combinedStatsWidget = ComponentName(context, CombinedStatsWidget::class.java)
        val feeRatesWidget = ComponentName(context, FeeRatesWidget::class.java)
        
        if (appWidgetManager.getAppWidgetIds(mempoolSizeWidget).isEmpty() &&
            appWidgetManager.getAppWidgetIds(combinedStatsWidget).isEmpty() &&
            appWidgetManager.getAppWidgetIds(feeRatesWidget).isEmpty()) {
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
        if (WidgetEventHandler.handleSystemEvent(context, intent, BlockHeightWidget::class.java, REFRESH_ACTION)) {
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
                    val thisWidget = ComponentName(context, BlockHeightWidget::class.java)
                    val widgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                    
                    // Just update the first widget to show error message
                    if (widgetIds.isNotEmpty()) {
                        val views = RemoteViews(context.packageName, R.layout.block_height_widget)
                        setErrorState(views)
                        appWidgetManager.updateAppWidget(widgetIds[0], views)
                    }
                    
                    return
                }
                
                // Network is available, proceed with update
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisWidget = ComponentName(context, BlockHeightWidget::class.java)
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
        val views = RemoteViews(context.packageName, R.layout.block_height_widget)

        // Create refresh intent
        val refreshIntent = Intent(context, BlockHeightWidget::class.java).apply {
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

        // Start new job
        activeJobs[appWidgetId] = getOrCreateScope().launch {
            var viewsPreparedForData = false
            try {
                Log.d(TAG, "Starting network request for widget update for ID: $appWidgetId")
                val mempoolApi = WidgetNetworkClient.getMempoolApi(context)

                val blockHeightDeferred = async(SupervisorJob() + coroutineContext) {
                    try { mempoolApi.getBlockHeight() } catch (e: Exception) { Log.e(TAG, "BH request failed for $appWidgetId: ${e.message}"); null }
                }
                val blockHashDeferred = async(SupervisorJob() + coroutineContext) {
                    try { mempoolApi.getLatestBlockHash() } catch (e: Exception) { Log.e(TAG, "Hash request failed for $appWidgetId: ${e.message}"); null }
                }

                val blockHeightResponse = blockHeightDeferred.await()

                if (blockHeightResponse != null && blockHeightResponse.isSuccessful) {
                    blockHeightResponse.body()?.let { blockHeight ->
                        views.setTextViewText(R.id.block_height, String.format(Locale.US, "%,d", blockHeight))
                        // Block height is primary data, consider this a partial success for now
                        viewsPreparedForData = true 

                        val blockHashResponse = blockHashDeferred.await()
                        if (blockHashResponse != null && blockHashResponse.isSuccessful) {
                            blockHashResponse.body()?.let { hash ->
                                try {
                                    val blockInfoResponse = mempoolApi.getBlockInfo(hash) // This can also fail
                                    if (blockInfoResponse.isSuccessful) {
                                        blockInfoResponse.body()?.timestamp?.let { timestamp ->
                                            val elapsedMinutes = (System.currentTimeMillis() / 1000 - timestamp) / 60
                                            views.setTextViewText(R.id.elapsed_time, "$elapsedMinutes ${if (elapsedMinutes == 1L) "minute" else "minutes"} ago")
                                        }
                                    } else {
                                        Log.w(TAG, "Block info response unsuccessful for $appWidgetId: ${blockInfoResponse.code()}")
                                        views.setTextViewText(R.id.elapsed_time, "") // Clear if failed
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error fetching block info for $appWidgetId: ${e.message}")
                                    views.setTextViewText(R.id.elapsed_time, "") // Clear on exception
                                }
                            } ?: Log.w(TAG, "Block hash body was null for $appWidgetId")
                        } else {
                            Log.w(TAG, "Block hash response unsuccessful or null for $appWidgetId. Code: ${blockHashResponse?.code()}")
                            views.setTextViewText(R.id.elapsed_time, "") // Clear if hash fetch failed
                        }
                        Log.d(TAG, "Data prepared (at least partially) for widget ID: $appWidgetId")
                    } ?: run {
                        Log.w(TAG, "Block height response body was null for $appWidgetId")
                        viewsPreparedForData = false // Explicitly mark as failed if block height body is null
                    }
                } else {
                    Log.w(TAG, "Block height response unsuccessful or null for $appWidgetId. Code: ${blockHeightResponse?.code()}")
                }

                if (!viewsPreparedForData) {
                    setErrorState(views)
                    Log.d(TAG, "Error state set for widget ID: $appWidgetId after data fetch attempt")
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Job for widget $appWidgetId was cancelled during try block.")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exception during widget update for ID: $appWidgetId", e)
                setErrorState(views)
                 Log.d(TAG, "Error state set for widget ID: $appWidgetId due to exception")
            } finally {
                val job = coroutineContext[Job]
                if (job?.isCancelled == false) {
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                    Log.d(TAG, "Final update in finally for widget $appWidgetId. Data: $viewsPreparedForData")
                } else {
                    Log.d(TAG, "Job for widget $appWidgetId was cancelled. Skipping final UI update in finally.")
                }
                activeJobs.remove(appWidgetId)
                WidgetUtils.resetTapState()
            }
        }
    }

    private fun setLoadingState(views: RemoteViews) {
        views.setTextViewText(R.id.block_height, "...")
        views.setTextViewText(R.id.elapsed_time, "")
    }

    private fun setErrorState(views: RemoteViews) {
        views.setTextViewText(R.id.block_height, "?")
        views.setTextViewText(R.id.elapsed_time, "")
    }
} 