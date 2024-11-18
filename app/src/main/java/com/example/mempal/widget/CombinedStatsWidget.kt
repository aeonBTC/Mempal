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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class CombinedStatsWidget : AppWidgetProvider() {
    companion object {
        private const val REFRESH_ACTION = "com.example.mempal.REFRESH_COMBINED_WIDGET"
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == REFRESH_ACTION) {
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

        // Set initial loading state
        setLoadingState(views)
        appWidgetManager.updateAppWidget(appWidgetId, views)

        // Fetch all data
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val blockHeightResponse = NetworkClient.mempoolApi.getBlockHeight()
                val mempoolInfoResponse = NetworkClient.mempoolApi.getMempoolInfo()
                val feeRatesResponse = NetworkClient.mempoolApi.getFeeRates()

                if (blockHeightResponse.isSuccessful && 
                    mempoolInfoResponse.isSuccessful && 
                    feeRatesResponse.isSuccessful) {
                    
                    val blockHeight = blockHeightResponse.body()
                    val mempoolInfo = mempoolInfoResponse.body()
                    val feeRates = feeRatesResponse.body()

                    if (blockHeight != null && mempoolInfo != null && feeRates != null) {
                        views.setTextViewText(R.id.block_height, 
                            String.format(Locale.US, "%,d", blockHeight))
                        views.setTextViewText(R.id.mempool_size, 
                            String.format(Locale.US, "%.2f vMB", mempoolInfo.vsize / 1_000_000.0))
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
        views.setTextViewText(R.id.mempool_size, "...")
        views.setTextViewText(R.id.priority_fee, "...")
        views.setTextViewText(R.id.standard_fee, "...")
        views.setTextViewText(R.id.economy_fee, "...")
    }
} 