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
    @SerializedName("total_fee") val totalFee: Long = 0L,
    @SerializedName("unconfirmed_count") val unconfirmedCount: Int = 0,
    @SerializedName("fee_histogram") val feeHistogram: List<List<Double>> = emptyList()
)

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String) : Result<Nothing>()
    object Loading : Result<Nothing>()
}