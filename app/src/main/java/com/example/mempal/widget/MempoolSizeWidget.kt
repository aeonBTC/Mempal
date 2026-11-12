package com.example.mempal.widget

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import com.example.mempal.R
import com.example.mempal.api.MempoolApi
import java.util.Locale
import kotlin.math.ceil

class MempoolSizeWidget : BaseMempalWidget() {
    
    companion object {
        const val REFRESH_ACTION = "com.example.mempal.REFRESH_MEMPOOL_SIZE_WIDGET"
        private const val TAG = "MempoolSizeWidget"
    }
    
    override val refreshAction: String = REFRESH_ACTION
    override val layoutResId: Int = R.layout.mempool_size_widget
    override val tag: String = TAG
    override val rootLayoutId: Int = R.id.widget_layout
    
    override val otherWidgetClasses: List<Class<out AppWidgetProvider>> = listOf(
        BlockHeightWidget::class.java,
        CombinedStatsWidget::class.java,
        FeeRatesWidget::class.java
    )
    
    override suspend fun fetchAndUpdateData(
        context: Context,
        api: MempoolApi,
        views: RemoteViews
    ): Boolean {
        return try {
            val mempoolInfo = WidgetDataProvider.fetchMempoolInfo(api) ?: return false

            val sizeInMB = mempoolInfo.vsize / 1_000_000.0
            views.setTextViewText(
                R.id.mempool_size,
                String.format(Locale.US, "%.2f vMB", sizeInMB)
            )

            val blocksToClean = ceil(sizeInMB / 1.5).toInt()
            views.setTextViewText(
                R.id.mempool_blocks_to_clear,
                "$blocksToClean ${if (blocksToClean == 1) "block" else "blocks"} to clear"
            )
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in fetchAndUpdateData: ${e.message}")
            false
        }
    }
    
    override fun setLoadingState(views: RemoteViews) {
        views.setTextViewText(R.id.mempool_size, "...")
        views.setTextViewText(R.id.mempool_blocks_to_clear, "")
    }
    
    override fun setErrorState(views: RemoteViews) {
        views.setTextViewText(R.id.mempool_size, "?")
        views.setTextViewText(R.id.mempool_blocks_to_clear, "")
    }
}
