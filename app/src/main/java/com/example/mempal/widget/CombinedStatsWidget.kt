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
import java.util.Locale
import kotlin.math.ceil

class CombinedStatsWidget : AppWidgetProvider() {
    companion object {
        const val REFRESH_ACTION = "com.example.mempal.REFRESH_COMBINED_WIDGET"
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
        val mempoolSizeWidget = ComponentName(context, MempoolSizeWidget::class.java)
        val feeRatesWidget = ComponentName(context, FeeRatesWidget::class.java)
        
        if (appWidgetManager.getAppWidgetIds(blockHeightWidget).isEmpty() &&
            appWidgetManager.getAppWidgetIds(mempoolSizeWidget).isEmpty() &&
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
                val thisWidget = ComponentName(context, CombinedStatsWidget::class.java)
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
        val views = RemoteViews(context.packageName, R.layout.combined_stats_widget)

        // Create refresh intent
        val refreshIntent = Intent(context, CombinedStatsWidget::class.java).apply {
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
                
                // Launch all API calls concurrently
                val blockHeightDeferred = async { mempoolApi.getBlockHeight() }
                val blockHashDeferred = async { mempoolApi.getLatestBlockHash() }
                val mempoolInfoDeferred = async { mempoolApi.getMempoolInfo() }
                val feeRatesDeferred = async { mempoolApi.getFeeRates() }
                
                // Process responses
                try {
                    val blockHeightResponse = blockHeightDeferred.await()
                    val mempoolInfoResponse = mempoolInfoDeferred.await()
                    val feeRatesResponse = feeRatesDeferred.await()
                    
                    if (blockHeightResponse.isSuccessful && 
                        mempoolInfoResponse.isSuccessful && 
                        feeRatesResponse.isSuccessful) {
                        
                        val blockHeight = blockHeightResponse.body()
                        val mempoolInfo = mempoolInfoResponse.body()
                        val feeRates = feeRatesResponse.body()
                        
                        if (blockHeight != null && mempoolInfo != null && feeRates != null) {
                            // Update block height
                            views.setTextViewText(R.id.block_height, 
                                String.format(Locale.US, "%,d", blockHeight))
                            
                            // Update mempool size
                            val sizeInMB = mempoolInfo.vsize / 1_000_000.0
                            views.setTextViewText(R.id.mempool_size, 
                                String.format(Locale.US, "%.2f vMB", sizeInMB))
                            
                            // Add blocks to clear calculation
                            val blocksToClean = ceil(sizeInMB / 1.5).toInt()
                            views.setTextViewText(R.id.mempool_blocks_to_clear,
                                "(${blocksToClean} ${if (blocksToClean == 1) "block" else "blocks"} to clear)")
                            
                            // Update fee rates
                            views.setTextViewText(R.id.priority_fee, "${feeRates.fastestFee}")
                            views.setTextViewText(R.id.standard_fee, "${feeRates.halfHourFee}")
                            views.setTextViewText(R.id.economy_fee, "${feeRates.hourFee}")
                            
                            // Try to get block timestamp
                            try {
                                val blockHashResponse = blockHashDeferred.await()
                                if (blockHashResponse.isSuccessful) {
                                    val hash = blockHashResponse.body()
                                    if (hash != null) {
                                        val blockInfoResponse = mempoolApi.getBlockInfo(hash)
                                        if (blockInfoResponse.isSuccessful) {
                                            blockInfoResponse.body()?.timestamp?.let { timestamp ->
                                                val elapsedMinutes = (System.currentTimeMillis() / 1000 - timestamp) / 60
                                                views.setTextViewText(R.id.elapsed_time, 
                                                    "(${elapsedMinutes} ${if (elapsedMinutes == 1L) "minute" else "minutes"} ago)")
                                            }
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                                // If timestamp fetch fails, just show other data
                                views.setTextViewText(R.id.elapsed_time, "")
                            }
                            
                            appWidgetManager.updateAppWidget(appWidgetId, views)
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    println("Error processing API responses: ${e.message}")
                }

                // If we get here, we didn't get any data
                setErrorState(views)
            } catch (e: Exception) {
                println("Error fetching widget data: ${e.message}")
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
        views.setTextViewText(R.id.mempool_size, "...")
        views.setTextViewText(R.id.mempool_blocks_to_clear, "")
        views.setTextViewText(R.id.priority_fee, "...")
        views.setTextViewText(R.id.standard_fee, "...")
        views.setTextViewText(R.id.economy_fee, "...")
    }

    private fun setErrorState(views: RemoteViews) {
        views.setTextViewText(R.id.block_height, "?")
        views.setTextViewText(R.id.elapsed_time, "")
        views.setTextViewText(R.id.mempool_size, "?")
        views.setTextViewText(R.id.mempool_blocks_to_clear, "")
        views.setTextViewText(R.id.priority_fee, "?")
        views.setTextViewText(R.id.standard_fee, "?")
        views.setTextViewText(R.id.economy_fee, "?")
    }
} 