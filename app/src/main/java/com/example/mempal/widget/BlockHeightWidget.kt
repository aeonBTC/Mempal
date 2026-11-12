package com.example.mempal.widget

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import com.example.mempal.R
import com.example.mempal.api.MempoolApi
import java.util.Locale

class BlockHeightWidget : BaseMempalWidget() {
    
    companion object {
        const val REFRESH_ACTION = "com.example.mempal.REFRESH_BLOCK_HEIGHT_WIDGET"
        private const val TAG = "BlockHeightWidget"
    }
    
    override val refreshAction: String = REFRESH_ACTION
    override val layoutResId: Int = R.layout.block_height_widget
    override val tag: String = TAG
    override val rootLayoutId: Int = R.id.widget_layout
    
    override val otherWidgetClasses: List<Class<out AppWidgetProvider>> = listOf(
        MempoolSizeWidget::class.java,
        CombinedStatsWidget::class.java,
        FeeRatesWidget::class.java
    )
    
    override suspend fun fetchAndUpdateData(
        context: Context,
        api: MempoolApi,
        views: RemoteViews
    ): Boolean {
        return try {
            val block = WidgetDataProvider.fetchLatestBlock(api) ?: return false

            val formattedHeight = String.format(Locale.US, "%,d", block.height) +
                if (block.height >= 1_000_000) " for" else ""
            views.setTextViewText(R.id.block_height, formattedHeight)

            val timestamp = block.timestamp
            if (timestamp != null) {
                val elapsedMinutes = (System.currentTimeMillis() / 1000 - timestamp) / 60
                views.setTextViewText(
                    R.id.elapsed_time,
                    "$elapsedMinutes ${if (elapsedMinutes == 1L) "minute" else "minutes"} ago"
                )
            } else {
                views.setTextViewText(R.id.elapsed_time, "")
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in fetchAndUpdateData: ${e.message}")
            false
        }
    }
    
    override fun setLoadingState(views: RemoteViews) {
        views.setTextViewText(R.id.block_height, "...")
        views.setTextViewText(R.id.elapsed_time, "")
    }
    
    override fun setErrorState(views: RemoteViews) {
        views.setTextViewText(R.id.block_height, "?")
        views.setTextViewText(R.id.elapsed_time, "")
    }
}
