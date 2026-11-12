package com.example.mempal.api

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Fetches the latest block using as few requests as possible.
 *
 * The mempool.space v1 blocks endpoint returns height, hash and timestamp together,
 * replacing the previous tip/height -> tip/hash -> block/{hash} chain with one call.
 * Servers that do not implement /api/v1/blocks fall back to the legacy chain.
 */
object BlockDataProvider {
    private const val TAG = "BlockDataProvider"

    data class Snapshot(
        val height: Int,
        val timestamp: Long?
    )

    suspend fun fetchLatestBlock(api: MempoolApi): Snapshot? {
        val recentBlocks = try {
            api.getRecentBlocks()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Recent blocks request failed: ${e.message}")
            null
        }

        if (recentBlocks?.isSuccessful == true) {
            try {
                val latest = recentBlocks.body()?.firstOrNull()
                if (latest != null && latest.height > 0) {
                    return Snapshot(
                        height = latest.height,
                        timestamp = latest.timestamp.takeIf { it > 0L }
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing recent blocks response: ${e.message}")
            }
        }

        return fetchLatestBlockLegacy(api)
    }

    /**
     * Compatibility path for servers that only expose the Esplora block endpoints.
     * Only used when /api/v1/blocks is unavailable or returns no usable data.
     */
    private suspend fun fetchLatestBlockLegacy(api: MempoolApi): Snapshot? {
        val heightResponse = try {
            api.getBlockHeight()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Legacy tip height request failed: ${e.message}")
            null
        }

        val height = if (heightResponse?.isSuccessful == true) heightResponse.body() else null
        if (height == null) return null

        val timestamp = try {
            val hash = api.getLatestBlockHash().takeIf { it.isSuccessful }?.body()
            if (hash != null) {
                api.getBlockInfo(hash).takeIf { it.isSuccessful }?.body()?.timestamp
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Legacy block info request failed: ${e.message}")
            null
        }

        return Snapshot(height, timestamp)
    }
}
