package com.example.mempal.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.mempal.R
import com.example.mempal.api.NetworkClient
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.math.ceil

class CombinedStatsWidget : AppWidgetProvider() {
    companion object {
        const val REFRESH_ACTION = "com.example.mempal.REFRESH_COMBINED_WIDGET"
        private var widgetScope: CoroutineScope? = null
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
        
        if (appWidgetManager.getAppWidgetIds(blockHeightWidget).isEmpty() &&
            appWidgetManager.getAppWidgetIds(mempoolSizeWidget).isEmpty()) {
            WidgetUpdater.cancelUpdates(context)
            // Cancel any ongoing coroutines
            widgetScope?.cancel()
            widgetScope = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == REFRESH_ACTION) {
            if (WidgetUtils.isDoubleTap()) {
                val launchIntent = WidgetUtils.getLaunchAppIntent(context)
                try {
                    launchIntent.send()
                    return
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, CombinedStatsWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.combined_stats_widget)
        
        val refreshIntent = Intent(context, CombinedStatsWidget::class.java).apply {
            action = REFRESH_ACTION
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_layout, refreshPendingIntent)

        setLoadingState(views)
        appWidgetManager.updateAppWidget(appWidgetId, views)

        getOrCreateScope().launch {
            try {
                val blockHeightResponse = NetworkClient.mempoolApi.getBlockHeight()
                val mempoolInfoResponse = NetworkClient.mempoolApi.getMempoolInfo()
                val feeRatesResponse = NetworkClient.mempoolApi.getFeeRates()
                
                // Get block timestamp
                var timestamp: Long? = null
                val blockHashResponse = NetworkClient.mempoolApi.getLatestBlockHash()
                if (blockHashResponse.isSuccessful) {
                    val hash = blockHashResponse.body()
                    if (hash != null) {
                        val blockInfoResponse = NetworkClient.mempoolApi.getBlockInfo(hash)
                        if (blockInfoResponse.isSuccessful) {
                            timestamp = blockInfoResponse.body()?.timestamp
                        }
                    }
                }

                if (blockHeightResponse.isSuccessful && 
                    mempoolInfoResponse.isSuccessful && 
                    feeRatesResponse.isSuccessful) {
                    
                    val blockHeight = blockHeightResponse.body()
                    val mempoolInfo = mempoolInfoResponse.body()
                    val feeRates = feeRatesResponse.body()

                    if (blockHeight != null && mempoolInfo != null && feeRates != null) {
                        views.setTextViewText(R.id.block_height, 
                            String.format(Locale.US, "%,d", blockHeight))
                            
                        timestamp?.let {
                            val elapsedMinutes = (System.currentTimeMillis() / 1000 - it) / 60
                            views.setTextViewText(R.id.elapsed_time, 
                                "(${elapsedMinutes} minutes ago)")
                        }
                        
                        views.setTextViewText(R.id.mempool_size, 
                            String.format(Locale.US, "%.2f vMB", mempoolInfo.vsize / 1_000_000.0))
                            
                        val blocksToClean = ceil(mempoolInfo.vsize / 1_000_000.0 / 1.5).toInt()
                        views.setTextViewText(R.id.mempool_blocks_to_clear,
                            "(${blocksToClean} blocks to clear)")
                            
                        views.setTextViewText(R.id.priority_fee, "${feeRates.fastestFee}")
                        views.setTextViewText(R.id.standard_fee, "${feeRates.halfHourFee}")
                        views.setTextViewText(R.id.economy_fee, "${feeRates.hourFee}")
                        
                        appWidgetManager.updateAppWidget(appWidgetId, views)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
} 