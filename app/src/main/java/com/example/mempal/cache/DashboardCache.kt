package com.example.mempal.cache

import com.example.mempal.api.FeeRates
import com.example.mempal.api.MempoolInfo

// Singleton object to store dashboard data in memory
object DashboardCache {
    private var blockHeight: Int? = null
    private var blockTimestamp: Long? = null
    private var mempoolInfo: MempoolInfo? = null
    private var feeRates: FeeRates? = null
    private var lastUpdateTime: Long? = null

    // Save all dashboard data at once
    fun saveState(
        blockHeight: Int?,
        blockTimestamp: Long?,
        mempoolInfo: MempoolInfo?,
        feeRates: FeeRates?
    ) {
        this.blockHeight = blockHeight
        this.blockTimestamp = blockTimestamp
        this.mempoolInfo = mempoolInfo
        this.feeRates = feeRates
        this.lastUpdateTime = System.currentTimeMillis()
    }

    // Get cached state
    fun getCachedState(): DashboardState {
        return DashboardState(
            blockHeight = blockHeight,
            blockTimestamp = blockTimestamp,
            mempoolInfo = mempoolInfo,
            feeRates = feeRates,
            lastUpdateTime = lastUpdateTime
        )
    }

    // Check if we have any cached data
    fun hasCachedData(): Boolean {
        return blockHeight != null || mempoolInfo != null || feeRates != null
    }

    // Clear cache (useful when app is closing)
    fun clearCache() {
        blockHeight = null
        blockTimestamp = null
        mempoolInfo = null
        feeRates = null
        lastUpdateTime = null
    }
}

// Data class to hold all dashboard state
data class DashboardState(
    val blockHeight: Int?,
    val blockTimestamp: Long?,
    val mempoolInfo: MempoolInfo?,
    val feeRates: FeeRates?,
    val lastUpdateTime: Long?
) 