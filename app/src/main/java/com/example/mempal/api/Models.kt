package com.example.mempal.api

import com.google.gson.annotations.SerializedName

data class FeeRates(
    @SerializedName("fastestFee") val fastestFee: Int = 0,
    @SerializedName("halfHourFee") val halfHourFee: Int = 0,
    @SerializedName("hourFee") val hourFee: Int = 0,
    @SerializedName("economyFee") val economyFee: Int = 0
)

data class MempoolInfo(
    @SerializedName("vsize") val vsize: Long = 0L,
    @SerializedName("total_fee") val totalFee: Double = 0.0,
    @SerializedName("unconfirmed_count") val unconfirmedCount: Int = 0,
    @SerializedName("fee_histogram") val feeHistogram: List<List<Double>> = emptyList(),
    val isUsingFallbackHistogram: Boolean = false
) {
    fun needsHistogramFallback(): Boolean = feeHistogram.isEmpty()
}