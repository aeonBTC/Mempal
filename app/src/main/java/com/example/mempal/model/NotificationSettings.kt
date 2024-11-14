package com.example.mempal.model

enum class FeeRateType {
    NEXT_BLOCK,
    TWO_BLOCKS,
    FOUR_BLOCKS
}

data class NotificationSettings(
    val blockNotificationsEnabled: Boolean = false,
    val blockCheckFrequency: Int = 0,
    val mempoolSizeNotificationsEnabled: Boolean = false,
    val mempoolCheckFrequency: Int = 0,
    val mempoolSizeThreshold: Float = 1f,
    val feeRatesNotificationsEnabled: Boolean = false,
    val feeRatesCheckFrequency: Int = 0,
    val selectedFeeRateType: FeeRateType = FeeRateType.NEXT_BLOCK,
    val feeRateThreshold: Int = 0,
    val isServiceEnabled: Boolean = false
)