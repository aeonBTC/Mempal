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
                // Single tap - refresh widget
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
                
                // Launch all API calls concurrently
                val blockHeightDeferred = async { mempoolApi.getBlockHeight() }
                val blockHashDeferred = async { mempoolApi.getLatestBlockHash() }
                val mempoolInfoDeferred = async { mempoolApi.getMempoolInfo() }
                val feeRatesDeferred = async { mempoolApi.getFeeRates() }
                
                // Process block height and timestamp
                try {
                    val blockHeightResponse = blockHeightDeferred.await()
                    if (blockHeightResponse.isSuccessful) {
                        blockHeightResponse.body()?.let { blockHeight ->
                            views.setTextViewText(R.id.block_height, 
                                String.format(Locale.US, "%,d", blockHeight))
                            hasAnyData = true
                            
                            // Process timestamp
                            try {
                                val blockHashResponse = blockHashDeferred.await()
                                if (blockHashResponse.isSuccessful) {
                                    val hash = blockHashResponse.body()
                                    if (hash != null) {
                                        val blockInfoDeferred = async { mempoolApi.getBlockInfo(hash) }
                                        val blockInfoResponse = blockInfoDeferred.await()
                                        if (blockInfoResponse.isSuccessful) {
                                            blockInfoResponse.body()?.timestamp?.let { timestamp ->
                                                val elapsedMinutes = (System.currentTimeMillis() / 1000 - timestamp) / 60
                                                views.setTextViewText(R.id.elapsed_time, 
                                                    "(${elapsedMinutes} minutes ago)")
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // If timestamp fetch fails, just clear the field
                                views.setTextViewText(R.id.elapsed_time, "")
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Process mempool info
                try {
                    val mempoolResponse = mempoolInfoDeferred.await()
                    if (mempoolResponse.isSuccessful) {
                        mempoolResponse.body()?.let { mempoolInfo ->
                            val sizeInMB = mempoolInfo.vsize / 1_000_000.0
                            views.setTextViewText(R.id.mempool_size, 
                                String.format(Locale.US, "%.2f vMB", sizeInMB))
                                
                            val blocksToClean = ceil(sizeInMB / 1.5).toInt()
                            views.setTextViewText(R.id.mempool_blocks_to_clear,
                                "(${blocksToClean} blocks to clear)")
                                
                            hasAnyData = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Process fee rates
                try {
                    val feeResponse = feeRatesDeferred.await()
                    if (feeResponse.isSuccessful) {
                        feeResponse.body()?.let { feeRates ->
                            views.setTextViewText(R.id.priority_fee, "${feeRates.fastestFee}")
                            views.setTextViewText(R.id.standard_fee, "${feeRates.halfHourFee}")
                            views.setTextViewText(R.id.economy_fee, "${feeRates.hourFee}")
                            hasAnyData = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (!hasAnyData) {
                    setErrorState(views, "No data")
                }
                
                appWidgetManager.updateAppWidget(appWidgetId, views)
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
        views.setTextViewText(R.id.block_height, "...")
        views.setTextViewText(R.id.elapsed_time, "")
        views.setTextViewText(R.id.mempool_size, "...")
        views.setTextViewText(R.id.mempool_blocks_to_clear, "")
        views.setTextViewText(R.id.priority_fee, "...")
        views.setTextViewText(R.id.standard_fee, "...")
        views.setTextViewText(R.id.economy_fee, "...")
    }

    private fun setErrorState(views: RemoteViews, error: String) {
        views.setTextViewText(R.id.block_height, "!")
        views.setTextViewText(R.id.elapsed_time, "")
        views.setTextViewText(R.id.mempool_size, "!")
        views.setTextViewText(R.id.mempool_blocks_to_clear, "")
        views.setTextViewText(R.id.priority_fee, "!")
        views.setTextViewText(R.id.standard_fee, "!")
        views.setTextViewText(R.id.economy_fee, "($error)")
    }
} 