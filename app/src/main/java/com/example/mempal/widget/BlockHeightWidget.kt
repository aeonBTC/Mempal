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
import java.util.*

class BlockHeightWidget : AppWidgetProvider() {
    companion object {
        const val REFRESH_ACTION = "com.example.mempal.REFRESH_BLOCK_HEIGHT_WIDGET"
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
        super.onReceive(context, intent)
        if (intent.action == REFRESH_ACTION) {
            if (WidgetUtils.isDoubleTap()) {
                // Launch app on double tap
                val launchIntent = WidgetUtils.getLaunchAppIntent(context)
                launchIntent.send()
            } else {
                // Single tap - refresh only this widget
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
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context, 0, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
            try {
                val mempoolApi = WidgetNetworkClient.getMempoolApi(context)
                
                // Launch both API calls concurrently
                val blockHeightDeferred = async { mempoolApi.getBlockHeight() }
                val blockHashDeferred = async { mempoolApi.getLatestBlockHash() }
                
                // Wait for block height first
                try {
                    val blockHeightResponse = blockHeightDeferred.await()
                    if (blockHeightResponse.isSuccessful) {
                        blockHeightResponse.body()?.let { blockHeight ->
                            views.setTextViewText(R.id.block_height, 
                                String.format(Locale.US, "%,d", blockHeight))
                            
                            // Process block hash and timestamp concurrently
                            try {
                                val blockHashResponse = blockHashDeferred.await()
                                if (blockHashResponse.isSuccessful) {
                                    val hash = blockHashResponse.body()
                                    if (hash != null) {
                                        // Launch block info request immediately
                                        val blockInfoDeferred = async { mempoolApi.getBlockInfo(hash) }
                                        val blockInfoResponse = blockInfoDeferred.await()
                                        if (blockInfoResponse.isSuccessful) {
                                            blockInfoResponse.body()?.timestamp?.let { timestamp ->
                                                val elapsedMinutes = (System.currentTimeMillis() / 1000 - timestamp) / 60
                                                views.setTextViewText(R.id.elapsed_time, 
                                                    "(${elapsedMinutes} ${if (elapsedMinutes == 1L) "minute" else "minutes"} ago)")
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // If timestamp fetch fails, just show block height
                                println("Error fetching block timestamp: ${e.message}")
                                e.printStackTrace()
                                views.setTextViewText(R.id.elapsed_time, "")
                            }
                            
                            // Update widget with at least block height
                            appWidgetManager.updateAppWidget(appWidgetId, views)
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // If we get here, we didn't get any data
                setErrorState(views)
            } catch (e: Exception) {
                e.printStackTrace()
                setErrorState(views)
            } finally {
                appWidgetManager.updateAppWidget(appWidgetId, views)
                activeJobs.remove(appWidgetId)
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