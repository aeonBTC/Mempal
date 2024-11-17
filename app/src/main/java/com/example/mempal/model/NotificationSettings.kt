package com.example.mempal.model

enum class FeeRateType {
    NEXT_BLOCK,
    TWO_BLOCKS,
    FOUR_BLOCKS,
    DAY_BLOCKS
}

data class NotificationSettings(
    val blockNotificationsEnabled: Boolean = false,
    val blockCheckFrequency: Int = 1,
    val mempoolSizeNotificationsEnabled: Boolean = false,
    val mempoolCheckFrequency: Int = 1,
    val mempoolSizeThreshold: Float = 10f,
    val feeRatesNotificationsEnabled: Boolean = false,
    val feeRatesCheckFrequency: Int = 1,
    val selectedFeeRateType: FeeRateType = FeeRateType.NEXT_BLOCK,
    val feeRateThreshold: Int = 1,
    val isServiceEnabled: Boolean = false,
    val txConfirmationEnabled: Boolean = false,
    val txConfirmationFrequency: Int = 1,
    val transactionId: String = "",
    val hasNotifiedForCurrentTx: Boolean = false,
    val hasNotifiedForMempoolSize: Boolean = false,
    val hasNotifiedForFeeRate: Boolean = false
)