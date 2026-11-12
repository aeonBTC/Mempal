package com.example.mempal.widget

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import com.example.mempal.R
import com.example.mempal.api.MempoolApi
import com.example.mempal.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Locale
import kotlin.math.ceil

class CombinedStatsWidget : BaseMempalWidget() {
    
    companion object {
        const val REFRESH_ACTION = "com.example.mempal.REFRESH_COMBINED_WIDGET"
        private const val TAG = "CombinedStatsWidget"
        
        private fun formatFeeRate(rate: Double): String {
            return if (rate % 1.0 == 0.0) {
                rate.toInt().toString()
            } else {
                String.format(Locale.US, "%.2f", rate)
            }
        }
    }
    
    override val refreshAction: String = REFRESH_ACTION
    override val layoutResId: Int = R.layout.combined_stats_widget
    override val tag: String = TAG
    override val rootLayoutId: Int = R.id.widget_layout
    
    override val otherWidgetClasses: List<Class<out AppWidgetProvider>> = listOf(
        BlockHeightWidget::class.java,
        MempoolSizeWidget::class.java,
        FeeRatesWidget::class.java
    )
    
    override suspend fun fetchAndUpdateData(
        context: Context,
        api: MempoolApi,
        views: RemoteViews
    ): Boolean = coroutineScope {
        var success = false
        
        try {
            val settingsRepository = SettingsRepository.getInstance(context)
            val usePreciseFees = settingsRepository.settings.value.usePreciseFees
            
            // Launch all requests in parallel. Every widget type shares these in-flight
            // requests, so an update burst performs one network call per endpoint.
            val blockDeferred = async(SupervisorJob() + coroutineContext) {
                WidgetDataProvider.fetchLatestBlock(api)
            }
            val mempoolInfoDeferred = async(SupervisorJob() + coroutineContext) {
                WidgetDataProvider.fetchMempoolInfo(api)
            }
            val feeRatesDeferred = async(SupervisorJob() + coroutineContext) {
                WidgetDataProvider.fetchFeeRates(api, usePreciseFees, settingsRepository)
            }
            
            val block = blockDeferred.await()
            val mempoolInfo = mempoolInfoDeferred.await()
            val feeRates = feeRatesDeferred.await()
            
            if (block != null && mempoolInfo != null && feeRates != null) {
                // Block height
                val formattedHeight = String.format(Locale.US, "%,d", block.height) + 
                    if (block.height >= 1_000_000) " for" else ""
                views.setTextViewText(R.id.block_height, formattedHeight)

                // Time since block (from the same response)
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
                
                // Mempool size
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
                
                // Fee rates
                views.setTextViewText(R.id.priority_fee, "${formatFeeRate(feeRates.fastestFee)} ")
                views.setTextViewText(R.id.standard_fee, "${formatFeeRate(feeRates.halfHourFee)} ")
                views.setTextViewText(R.id.economy_fee, "${formatFeeRate(feeRates.hourFee)} ")
                
                success = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in fetchAndUpdateData: ${e.message}")
        }
        
        success
    }
    
    override fun setLoadingState(views: RemoteViews) {
        views.setTextViewText(R.id.block_height, "...")
        views.setTextViewText(R.id.elapsed_time, "")
        views.setTextViewText(R.id.mempool_size, "...")
        views.setTextViewText(R.id.mempool_blocks_to_clear, "")
        views.setTextViewText(R.id.priority_fee, "...")
        views.setTextViewText(R.id.standard_fee, "...")
        views.setTextViewText(R.id.economy_fee, "...")
    }
    
    override fun setErrorState(views: RemoteViews) {
        views.setTextViewText(R.id.block_height, "?")
        views.setTextViewText(R.id.elapsed_time, "")
        views.setTextViewText(R.id.mempool_size, "?")
        views.setTextViewText(R.id.mempool_blocks_to_clear, "")
        views.setTextViewText(R.id.priority_fee, "?")
        views.setTextViewText(R.id.standard_fee, "?")
        views.setTextViewText(R.id.economy_fee, "?")
    }
}
