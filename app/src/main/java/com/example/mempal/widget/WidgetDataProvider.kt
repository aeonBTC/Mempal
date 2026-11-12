package com.example.mempal.widget

import android.util.Log
import com.example.mempal.api.BlockDataProvider
import com.example.mempal.api.FeeRates
import com.example.mempal.api.FeeRatesHelper
import com.example.mempal.api.MempoolApi
import com.example.mempal.api.MempoolInfo
import com.example.mempal.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Shares an in-flight widget request between all widget instances that are updating
 * at the same time. Results are never stored: once a request completes it is removed,
 * so the next widget update always performs a fresh network request.
 */
object WidgetDataProvider {
    private const val TAG = "WidgetDataProvider"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var blockInFlight: Deferred<BlockDataProvider.Snapshot?>? = null
    private var mempoolInFlight: Deferred<MempoolInfo?>? = null
    private var preciseFeesInFlight: Deferred<FeeRates?>? = null
    private var recommendedFeesInFlight: Deferred<FeeRates?>? = null

    suspend fun fetchLatestBlock(api: MempoolApi): BlockDataProvider.Snapshot? {
        val deferred = synchronized(lock) {
            blockInFlight ?: scope.async {
                try {
                    BlockDataProvider.fetchLatestBlock(api)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Block request failed: ${e.message}")
                    null
                }
            }.also { blockInFlight = it }
        }

        try {
            return deferred.await()
        } finally {
            synchronized(lock) {
                if (blockInFlight === deferred) blockInFlight = null
            }
        }
    }

    suspend fun fetchMempoolInfo(api: MempoolApi): MempoolInfo? {
        val deferred = synchronized(lock) {
            mempoolInFlight ?: scope.async {
                try {
                    api.getMempoolInfo().takeIf { it.isSuccessful }?.body()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Mempool info request failed: ${e.message}")
                    null
                }
            }.also { mempoolInFlight = it }
        }

        try {
            return deferred.await()
        } finally {
            synchronized(lock) {
                if (mempoolInFlight === deferred) mempoolInFlight = null
            }
        }
    }

    suspend fun fetchFeeRates(
        api: MempoolApi,
        usePreciseFees: Boolean,
        settingsRepository: SettingsRepository
    ): FeeRates? {
        val deferred = synchronized(lock) {
            val current = if (usePreciseFees) preciseFeesInFlight else recommendedFeesInFlight
            current ?: scope.async {
                try {
                    FeeRatesHelper.getFeeRatesWithFallback(api, usePreciseFees, settingsRepository)
                        ?.takeIf { it.isSuccessful }
                        ?.body()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Fee rates request failed: ${e.message}")
                    null
                }
            }.also {
                if (usePreciseFees) preciseFeesInFlight = it else recommendedFeesInFlight = it
            }
        }

        try {
            return deferred.await()
        } finally {
            synchronized(lock) {
                if (usePreciseFees) {
                    if (preciseFeesInFlight === deferred) preciseFeesInFlight = null
                } else {
                    if (recommendedFeesInFlight === deferred) recommendedFeesInFlight = null
                }
            }
        }
    }
}
