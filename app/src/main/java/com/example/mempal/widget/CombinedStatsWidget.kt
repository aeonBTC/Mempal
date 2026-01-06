package com.example.mempal.widget

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import com.example.mempal.R
import com.example.mempal.api.FeeRatesHelper
import com.example.mempal.api.MempoolApi
import com.example.mempal.repository.SettingsRepository
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
            
            // Launch all requests in parallel
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
            val mempoolInfoDeferred = async(SupervisorJob() + coroutineContext) {
                try { 
                    api.getMempoolInfo() 
                } catch (e: Exception) { 
                    Log.e(TAG, "Mempool info request failed: ${e.message}")
                    null 
                }
            }
            val feeRatesDeferred = async(SupervisorJob() + coroutineContext) {
                try {
                    FeeRatesHelper.getFeeRatesWithFallback(api, usePreciseFees, settingsRepository)
                } catch (e: Exception) { 
                    Log.e(TAG, "Fee rates request failed: ${e.message}")
                    null 
                }
            }
            
            val blockHeightResponse = blockHeightDeferred.await()
            val mempoolInfoResponse = mempoolInfoDeferred.await()
            val feeRatesResponse = feeRatesDeferred.await()
            
            if (blockHeightResponse?.isSuccessful == true && 
                mempoolInfoResponse?.isSuccessful == true && 
                feeRatesResponse?.isSuccessful == true) {
                
                val blockHeight = blockHeightResponse.body()
                val mempoolInfo = mempoolInfoResponse.body()
                val feeRates = feeRatesResponse.body()
                
                if (blockHeight != null && mempoolInfo != null && feeRates != null) {
                    // Block height
                    val formattedHeight = String.format(Locale.US, "%,d", blockHeight) + 
                        if (blockHeight >= 1_000_000) " for" else ""
                    views.setTextViewText(R.id.block_height, formattedHeight)
                    
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
                    
                    // Try to get block timestamp (secondary data)
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
