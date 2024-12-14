package com.example.mempal.model

enum class FeeRateType {
    NEXT_BLOCK,
    THREE_BLOCKS,
    SIX_BLOCKS,
    DAY_BLOCKS
}

data class NotificationSettings(
    val blockNotificationsEnabled: Boolean = false,
    val blockCheckFrequency: Int = 15,
    val newBlockNotificationEnabled: Boolean = false,
    val newBlockCheckFrequency: Int = 15,
    val hasNotifiedForNewBlock: Boolean = false,
    val specificBlockNotificationEnabled: Boolean = false,
    val specificBlockCheckFrequency: Int = 15,
    val targetBlockHeight: Int? = null,
    val hasNotifiedForTargetBlock: Boolean = false,
    val mempoolSizeNotificationsEnabled: Boolean = false,
    val mempoolCheckFrequency: Int = 15,
    val mempoolSizeThreshold: Float = 0f,
    val mempoolSizeAboveThreshold: Boolean = false,
    val feeRatesNotificationsEnabled: Boolean = false,
    val feeRatesCheckFrequency: Int = 15,
    val selectedFeeRateType: FeeRateType = FeeRateType.NEXT_BLOCK,
    val feeRateThreshold: Int = 0,
    val feeRateAboveThreshold: Boolean = false,
    val isServiceEnabled: Boolean = false,
    val txConfirmationEnabled: Boolean = false,
    val txConfirmationFrequency: Int = 15,
    val transactionId: String = "",
    val hasNotifiedForCurrentTx: Boolean = false,
    val hasNotifiedForMempoolSize: Boolean = false,
    val hasNotifiedForFeeRate: Boolean = false
) {
    companion object {
        const val MIN_CHECK_FREQUENCY = 1
    }
}