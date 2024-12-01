package com.example.mempal.model

enum class FeeRateType {
    NEXT_BLOCK,
    TWO_BLOCKS,
    FOUR_BLOCKS,
    DAY_BLOCKS
}

data class NotificationSettings(
    val blockNotificationsEnabled: Boolean = false,
    val blockCheckFrequency: Int = 10,
    val newBlockNotificationEnabled: Boolean = false,
    val newBlockCheckFrequency: Int = 10,
    val hasNotifiedForNewBlock: Boolean = false,
    val specificBlockNotificationEnabled: Boolean = false,
    val specificBlockCheckFrequency: Int = 10,
    val targetBlockHeight: Int? = null,
    val hasNotifiedForTargetBlock: Boolean = false,
    val mempoolSizeNotificationsEnabled: Boolean = false,
    val mempoolCheckFrequency: Int = 10,
    val mempoolSizeThreshold: Float = 10f,
    val mempoolSizeAboveThreshold: Boolean = false,
    val feeRatesNotificationsEnabled: Boolean = false,
    val feeRatesCheckFrequency: Int = 10,
    val selectedFeeRateType: FeeRateType = FeeRateType.NEXT_BLOCK,
    val feeRateThreshold: Int = 1,
    val feeRateAboveThreshold: Boolean = false,
    val isServiceEnabled: Boolean = false,
    val txConfirmationEnabled: Boolean = false,
    val txConfirmationFrequency: Int = 10,
    val transactionId: String = "",
    val hasNotifiedForCurrentTx: Boolean = false,
    val hasNotifiedForMempoolSize: Boolean = false,
    val hasNotifiedForFeeRate: Boolean = false
)