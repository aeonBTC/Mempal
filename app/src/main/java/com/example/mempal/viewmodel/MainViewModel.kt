package com.example.mempal.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mempal.api.FeeRates
import com.example.mempal.api.MempoolApi
import com.example.mempal.api.MempoolInfo
import com.example.mempal.api.NetworkClient
import com.example.mempal.cache.DashboardCache
import com.example.mempal.tor.TorManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.Response
import java.io.IOException

sealed class DashboardUiState {
    object Loading : DashboardUiState()
    data class Success(
        val isCache: Boolean = false
    ) : DashboardUiState()
    data class Error(
        val message: String,
        val isReconnecting: Boolean = false
    ) : DashboardUiState()
}

class MainViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState

    private val _isMainRefreshing = MutableStateFlow(false)
    val isMainRefreshing: StateFlow<Boolean> = _isMainRefreshing

    private val _blockHeight = MutableLiveData<Int?>(null)
    val blockHeight: LiveData<Int?> = _blockHeight

    private val _blockTimestamp = MutableLiveData<Long?>(null)
    val blockTimestamp: LiveData<Long?> = _blockTimestamp

    private val _feeRates = MutableLiveData<FeeRates?>(null)
    val feeRates: LiveData<FeeRates?> = _feeRates

    private val _mempoolInfo = MutableLiveData<MempoolInfo?>(null)
    val mempoolInfo: LiveData<MempoolInfo?> = _mempoolInfo

    var hasInitialData = false
        private set

    // Track which tabs have been loaded
    private var dashboardLoaded = false

    init {
        // Check if we have cached data immediately
        if (DashboardCache.hasCachedData()) {
            val cachedState = DashboardCache.getCachedState()
            _blockHeight.value = cachedState.blockHeight
            _blockTimestamp.value = cachedState.blockTimestamp
            _feeRates.value = cachedState.feeRates
            _mempoolInfo.value = cachedState.mempoolInfo
            // Don't set success state if we're not initialized, show reconnecting instead
            if (!NetworkClient.isInitialized.value) {
                _uiState.value = DashboardUiState.Error(
                    message = "Reconnecting to Tor network...",
                    isReconnecting = true
                )
            } else {
                _uiState.value = DashboardUiState.Success(isCache = true)
            }
            hasInitialData = true
        }
        
        // Monitor network initialization state
        viewModelScope.launch {
            NetworkClient.isInitialized.collect { initialized ->
                if (initialized) {
                    // Clear any error state before refreshing
                    if (_uiState.value is DashboardUiState.Error) {
                        _uiState.value = DashboardUiState.Loading
                    }
                    refreshDashboardData()
                    dashboardLoaded = true
                } else {
                    // Reset dashboard loaded state when network is not initialized
                    dashboardLoaded = false
                }
            }
        }

        // Monitor Tor connection state
        viewModelScope.launch {
            TorManager.getInstance().torConnectionEvent.collect { connected ->
                if (connected) {
                    // When Tor connects, show loading and refresh data
                    _uiState.value = DashboardUiState.Loading
                    refreshDashboardData()
                } else {
                    // Check if we have cache to determine if we're reconnecting
                    val isReconnecting = DashboardCache.hasCachedData()
                    val message = if (isReconnecting) "Reconnecting to Tor network..." else "Connecting to Tor network..."
                    _uiState.value = DashboardUiState.Error(message = message, isReconnecting = isReconnecting)
                }
            }
        }
    }

    // Function to handle tab changes
    fun onTabSelected(tab: Int) {
        when (tab) {
            0 -> if (!dashboardLoaded && NetworkClient.isInitialized.value) {
                refreshDashboardData()
                dashboardLoaded = true
            }
        }
    }

    // Load dashboard data only
    private fun refreshDashboardData() {
        if (!NetworkClient.isInitialized.value) {
            val isReconnecting = DashboardCache.hasCachedData()
            val message = if (isReconnecting) "Reconnecting to Tor network..." else "Connecting to Tor network..."
            _uiState.value = DashboardUiState.Error(message = message, isReconnecting = isReconnecting)
            return
        }

        viewModelScope.launch {
            // Show loading state if we're reconnecting or don't have initial data
            if (!hasInitialData || _uiState.value is DashboardUiState.Error) {
                _uiState.value = DashboardUiState.Loading
            }

            try {
                println("Starting parallel API calls...")

                coroutineScope {
                    // Launch all API calls in parallel using async
                    val blockHeightDeferred = async { NetworkClient.mempoolApi.getBlockHeight() }
                    val feeRatesDeferred = async { NetworkClient.mempoolApi.getFeeRates() }
                    val mempoolInfoDeferred = async { NetworkClient.mempoolApi.getMempoolInfo() }
                    val blockHashDeferred = async { NetworkClient.mempoolApi.getLatestBlockHash() }

                    // Wait for all responses
                    val blockHeightResponse = blockHeightDeferred.await()
                    val feeRatesResponse = feeRatesDeferred.await()
                    val mempoolInfoResponse = mempoolInfoDeferred.await()
                    val blockHashResponse = blockHashDeferred.await()

                    var timestamp: Long? = null
                    if (blockHashResponse.isSuccessful) {
                        blockHashResponse.body()?.let { hash ->
                            val blockInfoResponse = NetworkClient.mempoolApi.getBlockInfo(hash)
                            if (blockInfoResponse.isSuccessful) {
                                timestamp = blockInfoResponse.body()?.timestamp
                            }
                        }
                    }

                    processResponses(blockHeightResponse, feeRatesResponse, mempoolInfoResponse, timestamp)
                }

                // Ensure we have mempool info even if the parallel call failed
                if (_mempoolInfo.value == null) {
                    refreshMempoolInfo()
                }
                
                hasInitialData = true
            } catch (e: Exception) {
                println("Error refreshing data: ${e.message}")
                e.printStackTrace()
                handleError(e)
            }
        }
    }

    // Manual refresh for dashboard
    fun refreshData() {
        _isMainRefreshing.value = true
        refreshDashboardData()
    }

    private fun processResponses(
        blockHeightResponse: Response<Int>,
        feeRatesResponse: Response<FeeRates>,
        mempoolInfoResponse: Response<MempoolInfo>,
        timestamp: Long?
    ) {
        var hasAnySuccessfulResponse = false

        // Process block height
        if (blockHeightResponse.isSuccessful) {
            val blockHeight = blockHeightResponse.body()
            _blockHeight.value = blockHeight
            _blockTimestamp.value = timestamp
            hasAnySuccessfulResponse = true
            DashboardCache.saveState(
                blockHeight = blockHeight,
                blockTimestamp = timestamp,
                mempoolInfo = _mempoolInfo.value,
                feeRates = _feeRates.value
            )
        }

        // Process fee rates
        if (feeRatesResponse.isSuccessful) {
            val feeRates = feeRatesResponse.body()
            _feeRates.value = feeRates
            hasAnySuccessfulResponse = true
            DashboardCache.saveState(
                blockHeight = _blockHeight.value,
                blockTimestamp = _blockTimestamp.value,
                mempoolInfo = _mempoolInfo.value,
                feeRates = feeRates
            )
        }

        // Process mempool info
        if (mempoolInfoResponse.isSuccessful) {
            val mempoolInfo = mempoolInfoResponse.body()
            hasAnySuccessfulResponse = true

            // Update mempool info immediately for size display
            _mempoolInfo.value = mempoolInfo

            // Save state immediately with current mempool info
            DashboardCache.saveState(
                blockHeight = _blockHeight.value,
                blockTimestamp = _blockTimestamp.value,
                mempoolInfo = mempoolInfo,
                feeRates = _feeRates.value
            )

            // Check if mempool info needs fallback for fee distribution
            if (mempoolInfo != null && mempoolInfo.needsHistogramFallback()) {
                viewModelScope.launch {
                    try {
                        val fallbackClient = NetworkClient.createTestClient(MempoolApi.BASE_URL)
                        val fallbackResponse = fallbackClient.getMempoolInfo()
                        if (fallbackResponse.isSuccessful && fallbackResponse.body() != null) {
                            val fallbackInfo = fallbackResponse.body()!!
                            if (!fallbackInfo.needsHistogramFallback()) {
                                _mempoolInfo.value = mempoolInfo.withFallbackHistogram(fallbackInfo.feeHistogram)
                                // Update cache with fallback data
                                DashboardCache.saveState(
                                    blockHeight = _blockHeight.value,
                                    blockTimestamp = _blockTimestamp.value,
                                    mempoolInfo = _mempoolInfo.value,
                                    feeRates = _feeRates.value
                                )
                            }
                        }
                    } catch (_: Exception) {
                        // Fallback failed, but we already have the mempool size displayed
                    }
                }
            }
        }

        if (hasAnySuccessfulResponse) {
            // Always set success state with fresh data if we have any successful response
            _uiState.value = DashboardUiState.Success(isCache = false)
            hasInitialData = true
            _isMainRefreshing.value = false
        } else {
            // If we have cached data, use it instead of showing an error
            if (DashboardCache.hasCachedData()) {
                val cachedState = DashboardCache.getCachedState()
                _blockHeight.value = cachedState.blockHeight
                _blockTimestamp.value = cachedState.blockTimestamp
                _feeRates.value = cachedState.feeRates
                _mempoolInfo.value = cachedState.mempoolInfo
                
                // Only show cached state if we're not in an error state
                if (_uiState.value !is DashboardUiState.Error) {
                    _uiState.value = DashboardUiState.Success(isCache = true)
                }
            } else {
                handleError(Exception("No API calls were successful"))
            }
            _isMainRefreshing.value = false
        }
    }

    private fun handleError(e: Exception) {
        // If we have cached data, use it instead of showing an error
        if (DashboardCache.hasCachedData()) {
            val cachedState = DashboardCache.getCachedState()
            _blockHeight.value = cachedState.blockHeight
            _blockTimestamp.value = cachedState.blockTimestamp
            _feeRates.value = cachedState.feeRates
            _mempoolInfo.value = cachedState.mempoolInfo
            
            _uiState.value = DashboardUiState.Success(isCache = true)
        } else {
            val message = when (e) {
                is IOException -> "Connecting to Tor network..."
                else -> e.message ?: "Unknown error occurred"
            }
            _uiState.value = DashboardUiState.Error(message = message, isReconnecting = false)
        }
        _isMainRefreshing.value = false
    }

    override fun onCleared() {
        super.onCleared()
        // Clear cache when ViewModel is destroyed
        DashboardCache.clearCache()
    }

    // Individual refresh functions for each card
    fun refreshBlockData() {
        if (!NetworkClient.isInitialized.value) {
            val isReconnecting = DashboardCache.hasCachedData()
            val message = if (isReconnecting) "Reconnecting to Tor network..." else "Connecting to Tor network..."
            _uiState.value = DashboardUiState.Error(message = message, isReconnecting = isReconnecting)
            return
        }
        viewModelScope.launch {
            try {
                val blockHeightResponse = NetworkClient.mempoolApi.getBlockHeight()
                val blockHashResponse = NetworkClient.mempoolApi.getLatestBlockHash()
                
                if (blockHeightResponse.isSuccessful) {
                    _blockHeight.value = blockHeightResponse.body()
                }

                if (blockHashResponse.isSuccessful) {
                    blockHashResponse.body()?.let { hash ->
                        val blockInfoResponse = NetworkClient.mempoolApi.getBlockInfo(hash)
                        if (blockInfoResponse.isSuccessful) {
                            _blockTimestamp.value = blockInfoResponse.body()?.timestamp
                        }
                    }
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun refreshFeeRates() {
        if (!NetworkClient.isInitialized.value) {
            val isReconnecting = DashboardCache.hasCachedData()
            val message = if (isReconnecting) "Reconnecting to Tor network..." else "Connecting to Tor network..."
            _uiState.value = DashboardUiState.Error(message = message, isReconnecting = isReconnecting)
            return
        }
        viewModelScope.launch {
            try {
                val response = NetworkClient.mempoolApi.getFeeRates()
                if (response.isSuccessful) {
                    _feeRates.value = response.body()
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun refreshMempoolInfo() {
        if (!NetworkClient.isInitialized.value) {
            val isReconnecting = DashboardCache.hasCachedData()
            val message = if (isReconnecting) "Reconnecting to Tor network..." else "Connecting to Tor network..."
            _uiState.value = DashboardUiState.Error(message = message, isReconnecting = isReconnecting)
            return
        }
        viewModelScope.launch {
            try {
                // Try primary server first
                val response = NetworkClient.mempoolApi.getMempoolInfo()
                var mempoolInfo = response.body()
                
                // Check if we need histogram fallback
                if (response.isSuccessful && mempoolInfo != null && mempoolInfo.needsHistogramFallback()) {
                    try {
                        println("Primary server missing histogram data, trying fallback...")
                        val fallbackClient = NetworkClient.createTestClient(MempoolApi.BASE_URL)
                        val fallbackResponse = fallbackClient.getMempoolInfo()
                        
                        if (fallbackResponse.isSuccessful && fallbackResponse.body() != null) {
                            val fallbackInfo = fallbackResponse.body()!!
                            if (!fallbackInfo.needsHistogramFallback()) {
                                println("Got histogram data from fallback server")
                                // Keep original data but use fallback histogram
                                _mempoolInfo.value = mempoolInfo.withFallbackHistogram(fallbackInfo.feeHistogram)
                                return@launch
                            }
                        }
                    } catch (e: Exception) {
                        println("Fallback server failed: ${e.message}")
                    }
                }
                
                // If we get here, either:
                // 1. Primary server succeeded with histogram data
                // 2. Primary server succeeded without histogram but fallback failed
                // 3. Primary server failed
                if (response.isSuccessful && mempoolInfo != null) {
                    _mempoolInfo.value = mempoolInfo
                } else {
                    handleError(IOException("Failed to fetch mempool info"))
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }
}