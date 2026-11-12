package com.example.mempal.api

import com.google.gson.annotations.SerializedName

data class FeeRates(
    @SerializedName("fastestFee") val fastestFee: Double = 0.0,
    @SerializedName("halfHourFee") val halfHourFee: Double = 0.0,
    @SerializedName("hourFee") val hourFee: Double = 0.0,
    @SerializedName("economyFee") val economyFee: Double = 0.0,
    val isUsingFallbackPreciseFees: Boolean = false
)

data class MempoolBlock(
    @SerializedName("id") val id: String = "",
    @SerializedName("height") val height: Int = 0,
    @SerializedName("timestamp") val timestamp: Long = 0L
)

data class MempoolInfo(
    @SerializedName("vsize") val vsize: Long = 0L,
    @SerializedName("total_fee") val totalFee: Double = 0.0,
    @SerializedName("count") val unconfirmedCount: Int = 0,
    @SerializedName("fee_histogram") val feeHistogram: List<List<Double>> = emptyList(),
    val isUsingFallbackHistogram: Boolean = false
) {
    fun needsHistogramFallback(): Boolean = feeHistogram.isEmpty()
}