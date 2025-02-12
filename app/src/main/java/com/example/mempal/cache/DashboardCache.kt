package com.example.mempal.cache

import com.example.mempal.api.FeeRates
import com.example.mempal.api.HashrateInfo
import com.example.mempal.api.MempoolInfo

// Singleton object to store dashboard data in memory
object DashboardCache {
    private var cachedBlockHeight: Int? = null
    private var cachedBlockTimestamp: Long? = null
    private var cachedFeeRates: FeeRates? = null
    private var cachedMempoolInfo: MempoolInfo? = null
    private var cachedHashrateInfo: HashrateInfo? = null

    // Save all dashboard data at once
    fun saveState(
        blockHeight: Int?,
        blockTimestamp: Long?,
        mempoolInfo: MempoolInfo?,
        feeRates: FeeRates?
    ) {
        cachedBlockHeight = blockHeight
        cachedBlockTimestamp = blockTimestamp
        cachedFeeRates = feeRates
        cachedMempoolInfo = mempoolInfo
        cachedHashrateInfo = null
    }

    // Get cached state
    fun getCachedState(): DashboardState {
        return DashboardState(
            blockHeight = cachedBlockHeight,
            blockTimestamp = cachedBlockTimestamp,
            mempoolInfo = cachedMempoolInfo,
            feeRates = cachedFeeRates,
            hashrateInfo = cachedHashrateInfo
        )
    }

    // Check if we have any cached data
    fun hasCachedData(): Boolean {
        return cachedBlockHeight != null || 
               cachedBlockTimestamp != null || 
               cachedFeeRates != null || 
               cachedMempoolInfo != null ||
               cachedHashrateInfo != null
    }

    // Clear cache (useful when app is closing)
    fun clearCache() {
        cachedBlockHeight = null
        cachedBlockTimestamp = null
        cachedFeeRates = null
        cachedMempoolInfo = null
        cachedHashrateInfo = null
    }
}

// Data class to hold all dashboard state
data class DashboardState(
    val blockHeight: Int?,
    val blockTimestamp: Long?,
    val mempoolInfo: MempoolInfo?,
    val feeRates: FeeRates?,
    val hashrateInfo: HashrateInfo?
) 