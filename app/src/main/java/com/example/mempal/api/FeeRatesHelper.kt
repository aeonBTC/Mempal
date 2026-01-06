package com.example.mempal.api

import android.util.Log
import com.example.mempal.repository.SettingsRepository
import com.example.mempal.tor.TorManager
import com.example.mempal.tor.TorStatus
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.net.SocketTimeoutException
import java.io.IOException

/**
 * Shared utility for fetching fee rates with fallback support.
 * Consolidates duplicated logic from MainViewModel, NotificationService, and widgets.
 */
object FeeRatesHelper {
    private const val TAG = "FeeRatesHelper"

    /**
     * Fetches fee rates with fallback support for precise fees.
     * 
     * @param api The MempoolApi instance to use for requests
     * @param usePreciseFees Whether to attempt fetching precise fee rates
     * @param settingsRepository Repository to check current API URL settings
     * @return Response containing FeeRates, or null if all attempts fail
     */
    suspend fun getFeeRatesWithFallback(
        api: MempoolApi,
        usePreciseFees: Boolean,
        settingsRepository: SettingsRepository
    ): Response<FeeRates>? {
        // If not using precise fees, just get regular rates
        if (!usePreciseFees) {
            return try {
                api.getFeeRates()
            } catch (e: CancellationException) {
                throw e // Rethrow cancellation exceptions
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching regular fee rates: ${e.message}")
                null
            }
        }

        // Check if we're using a custom server (needs fallback)
        val isCustomServer = try {
            val apiUrl = settingsRepository.getApiUrl()
            apiUrl != MempoolApi.BASE_URL.trimEnd('/') &&
            apiUrl != MempoolApi.ONION_BASE_URL.trimEnd('/')
        } catch (_: Exception) {
            false
        }

        // Try precise fees from current server first
        var response: Response<FeeRates>? = null
        var serverReachable: Boolean

        try {
            response = api.getPreciseFeeRates()
            serverReachable = true
        } catch (e: CancellationException) {
            throw e // Rethrow cancellation exceptions
        } catch (e: SocketTimeoutException) {
            // Server unreachable - try fallback if custom server, otherwise show error
            Log.e(TAG, "Server unreachable (timeout): ${e.message}")
            if (isCustomServer) {
                val fallbackResponse = tryFallbackPreciseFees()
                if (fallbackResponse != null) {
                    return fallbackResponse
                }
            }
            return null
        } catch (e: IOException) {
            // Server unreachable - try fallback if custom server, otherwise show error
            Log.e(TAG, "Server unreachable (IO error): ${e.message}")
            if (isCustomServer) {
                val fallbackResponse = tryFallbackPreciseFees()
                if (fallbackResponse != null) {
                    return fallbackResponse
                }
            }
            return null
        } catch (e: Exception) {
            // Other errors - might be server reachable but error response (e.g., 404, 500)
            Log.e(TAG, "Error calling getPreciseFeeRates: ${e.message}")
            // For custom servers, try fallback even on exceptions
            if (isCustomServer) {
                val fallbackResponse = tryFallbackPreciseFees()
                if (fallbackResponse != null) {
                    return fallbackResponse
                }
            }
            // If we can't determine if server is reachable, try fallback anyway for custom servers
            serverReachable = false
        }

        // Check if the response is successful AND has valid data
        if (response != null && response.isSuccessful) {
            try {
                val body = response.body()
                if (body != null && body.hasValidData()) {
                    // For custom servers, always try fallback first to get actual precise fees
                    // Some custom servers return 200 OK with regular fees when precise endpoint is called
                    if (isCustomServer) {
                        val fallbackResponse = tryFallbackPreciseFees()
                        if (fallbackResponse != null) {
                            // Fallback succeeded, use it instead
                            closeResponseSafely(response)
                            return fallbackResponse
                        }
                        // Fallback failed, use custom server's response
                    }
                    return response
                } else {
                    // Response successful but invalid data - try fallback for custom servers
                    Log.w(TAG, "Precise fee rates response has invalid data")
                    closeResponseSafely(response)
                    if (isCustomServer) {
                        val fallbackResponse = tryFallbackPreciseFees()
                        if (fallbackResponse != null) {
                            return fallbackResponse
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing precise fee rates response: ${e.message}")
                closeResponseSafely(response)
                // Try fallback for custom servers even on parse errors
                if (isCustomServer) {
                    val fallbackResponse = tryFallbackPreciseFees()
                    if (fallbackResponse != null) {
                        return fallbackResponse
                    }
                }
            }
        } else if (response != null) {
            // Response not successful (e.g., 404, 500) - try fallback for custom servers
            Log.w(TAG, "Precise fee rates request failed with code: ${response.code()}")
            closeResponseSafely(response)
            if (isCustomServer) {
                val fallbackResponse = tryFallbackPreciseFees()
                if (fallbackResponse != null) {
                    return fallbackResponse
                }
            }
        }

        // Server is reachable but missing data - try fallback if custom server (one more time)
        if (isCustomServer && serverReachable) {
            val fallbackResponse = tryFallbackPreciseFees()
            if (fallbackResponse != null) {
                return fallbackResponse
            }
        }

        // Final fallback: regular (non-precise) fees from current server
        // Only if we got a response (server is reachable)
        if (serverReachable) {
            return try {
                api.getFeeRates()
            } catch (e: CancellationException) {
                throw e // Rethrow cancellation exceptions
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching fallback regular fee rates: ${e.message}")
                null
            }
        }

        // No response means server unreachable - return null to show error
        return null
    }

    /**
     * Attempts to fetch precise fee rates from mempool.space as fallback.
     */
    private suspend fun tryFallbackPreciseFees(): Response<FeeRates>? {
        return try {
            val torManager = TorManager.getInstance()
            val isUsingTor = torManager.isTorEnabled() && 
                             torManager.torStatus.value == TorStatus.CONNECTED
            
            val fallbackUrl = if (isUsingTor) {
                MempoolApi.ONION_BASE_URL
            } else {
                MempoolApi.BASE_URL
            }
            
            val fallbackClient = NetworkClient.createTestClient(
                baseUrl = fallbackUrl,
                useTor = isUsingTor
            )
            
            val fallbackResponse = fallbackClient.getPreciseFeeRates()
            
            if (fallbackResponse.isSuccessful) {
                try {
                    val fallbackBody = fallbackResponse.body()
                    if (fallbackBody != null && fallbackBody.hasValidData()) {
                        // Mark that we're using fallback
                        return Response.success(
                            fallbackBody.copy(isUsingFallbackPreciseFees = true)
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing fallback precise fee rates: ${e.message}")
                    closeResponseSafely(fallbackResponse)
                }
            }
            null
        } catch (e: CancellationException) {
            throw e // Rethrow cancellation exceptions
        } catch (e: Exception) {
            Log.e(TAG, "Fallback to mempool.space precise fees failed: ${e.message}")
            null
        }
    }

    /**
     * Safely closes the response body to prevent resource leaks.
     */
    private fun closeResponseSafely(response: Response<*>) {
        try {
            response.raw().body?.close()
        } catch (_: Exception) {
            // Ignore errors when closing
        }
    }

    /**
     * Extension function to check if FeeRates has valid data.
     */
    private fun FeeRates.hasValidData(): Boolean {
        return fastestFee > 0 || halfHourFee > 0 || hourFee > 0 || economyFee > 0
    }
}
