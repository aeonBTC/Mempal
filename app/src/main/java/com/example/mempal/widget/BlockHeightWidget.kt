package com.example.mempal.widget

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import com.example.mempal.R
import com.example.mempal.api.MempoolApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    ): Boolean = coroutineScope {
        var success = false
        
        try {
            val blockHeightDeferred = async(SupervisorJob() + coroutineContext) {
                try { 
                    api.getBlockHeight() 
                } catch (e: Exception) { 
                    Log.e(TAG, "Block height request failed: ${e.message}")
                    null 
                }
            }
            val blockHashDeferred = async(SupervisorJob() + coroutineContext) {
                try { 
                    api.getLatestBlockHash() 
                } catch (e: Exception) { 
                    Log.e(TAG, "Block hash request failed: ${e.message}")
                    null 
                }
            }
            
            val blockHeightResponse = blockHeightDeferred.await()
            
            if (blockHeightResponse?.isSuccessful == true) {
                blockHeightResponse.body()?.let { blockHeight ->
                    val formattedHeight = String.format(Locale.US, "%,d", blockHeight) + 
                        if (blockHeight >= 1_000_000) " for" else ""
                    views.setTextViewText(R.id.block_height, formattedHeight)
                    success = true
                    
                    // Try to get elapsed time (secondary data)
                    val blockHashResponse = blockHashDeferred.await()
                    if (blockHashResponse?.isSuccessful == true) {
                        blockHashResponse.body()?.let { hash ->
                            try {
                                val blockInfoResponse = api.getBlockInfo(hash)
                                if (blockInfoResponse.isSuccessful) {
                                    blockInfoResponse.body()?.timestamp?.let { timestamp ->
                                        val elapsedMinutes = (System.currentTimeMillis() / 1000 - timestamp) / 60
                                        views.setTextViewText(
                                            R.id.elapsed_time,
                                            "$elapsedMinutes ${if (elapsedMinutes == 1L) "minute" else "minutes"} ago"
                                        )
                                    }
                                } else {
                                    views.setTextViewText(R.id.elapsed_time, "")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error fetching block info: ${e.message}")
                                views.setTextViewText(R.id.elapsed_time, "")
                            }
                        }
                    } else {
                        views.setTextViewText(R.id.elapsed_time, "")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in fetchAndUpdateData: ${e.message}")
        }
        
        success
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
