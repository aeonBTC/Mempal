package com.example.mempal.widget

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import com.example.mempal.R
import com.example.mempal.api.MempoolApi
import com.example.mempal.repository.SettingsRepository
import java.util.Locale

class FeeRatesWidget : BaseMempalWidget() {
    
    companion object {
        const val REFRESH_ACTION = "com.example.mempal.REFRESH_FEE_RATES_WIDGET"
        private const val TAG = "FeeRatesWidget"
        
        private fun formatFeeRate(rate: Double): String {
            return if (rate % 1.0 == 0.0) {
                rate.toInt().toString()
            } else {
                String.format(Locale.US, "%.2f", rate)
            }
        }
    }
    
    override val refreshAction: String = REFRESH_ACTION
    override val layoutResId: Int = R.layout.fee_rates_widget
    override val tag: String = TAG
    override val rootLayoutId: Int = R.id.widget_layout
    
    override val otherWidgetClasses: List<Class<out AppWidgetProvider>> = listOf(
        BlockHeightWidget::class.java,
        CombinedStatsWidget::class.java,
        MempoolSizeWidget::class.java
    )
    
    override suspend fun fetchAndUpdateData(
        context: Context,
        api: MempoolApi,
        views: RemoteViews
    ): Boolean {
        return try {
            val settingsRepository = SettingsRepository.getInstance(context)
            val usePreciseFees = settingsRepository.settings.value.usePreciseFees
            val feeRates = WidgetDataProvider.fetchFeeRates(api, usePreciseFees, settingsRepository)
                ?: return false

            views.setTextViewText(R.id.priority_fee, formatFeeRate(feeRates.fastestFee))
            views.setTextViewText(R.id.standard_fee, formatFeeRate(feeRates.halfHourFee))
            views.setTextViewText(R.id.economy_fee, formatFeeRate(feeRates.hourFee))
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in fetchAndUpdateData: ${e.message}")
            false
        }
    }
    
    override fun setLoadingState(views: RemoteViews) {
        views.setTextViewText(R.id.priority_fee, "...")
        views.setTextViewText(R.id.standard_fee, "...")
        views.setTextViewText(R.id.economy_fee, "...")
    }
    
    override fun setErrorState(views: RemoteViews) {
        views.setTextViewText(R.id.priority_fee, "?")
        views.setTextViewText(R.id.standard_fee, "?")
        views.setTextViewText(R.id.economy_fee, "?")
    }
}
