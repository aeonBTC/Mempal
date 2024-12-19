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
import com.example.mempal.tor.TorStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.Response

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

    private var serverNeedsFallback = false

    init {
        // Check if we have cached data immediately
        if (DashboardCache.hasCachedData()) {
            val cachedState = DashboardCache.getCachedState()
            _blockHeight.value = cachedState.blockHeight
            _blockTimestamp.value = cachedState.blockTimestamp
            _feeRates.value = cachedState.feeRates
            _mempoolInfo.value = cachedState.mempoolInfo
            
            // Set initial state based on Tor status if using Tor
            if (TorManager.getInstance().isTorEnabled()) {
                val message = if (DashboardCache.hasCachedData()) {
                    "Reconnecting to Tor network..."
                } else {
                    "Connecting to Tor network..."
                }
                _uiState.value = DashboardUiState.Error(message = message, isReconnecting = true)
            } else if (!NetworkClient.isInitialized.value) {
                _uiState.value = DashboardUiState.Error(
                    message = "Connecting to server...",
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
                    // Only refresh if we're not waiting for Tor
                    val torManager = TorManager.getInstance()
                    val torStatus = torManager.torStatus.value
                    if (!torManager.isTorEnabled() || torStatus == TorStatus.CONNECTED) {
                        _uiState.value = DashboardUiState.Loading
                        refreshDashboardData()
                        dashboardLoaded = true
                    }
                } else {
                    // Reset dashboard loaded state when network is not initialized
                    dashboardLoaded = false
                }
            }
        }

        // Monitor Tor connection state
        viewModelScope.launch {
            TorManager.getInstance().torStatus.collect { status ->
                when (status) {
                    TorStatus.CONNECTED -> {
                        // When Tor connects, show loading and refresh data
                        _uiState.value = DashboardUiState.Loading
                        refreshDashboardData()
                    }
                    TorStatus.CONNECTING -> {
                        // Show connecting message
                        val message = if (DashboardCache.hasCachedData()) {
                            "Reconnecting to Tor network..."
                        } else {
                            "Connecting to Tor network..."
                        }
                        _uiState.value = DashboardUiState.Error(message = message, isReconnecting = true)
                    }
                    else -> {
                        if (TorManager.getInstance().isTorEnabled()) {
                            // Only show Tor-related messages if Tor is enabled
                            val message = if (DashboardCache.hasCachedData()) {
                                "Reconnecting to Tor network..."
                            } else {
                                "Connecting to Tor network..."
                            }
                            _uiState.value = DashboardUiState.Error(message = message, isReconnecting = true)
                        }
                    }
                }
            }
        }
    }

    // Function to handle tab changes
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
            val torManager = TorManager.getInstance()
            if (torManager.isTorEnabled()) {
                val message = if (DashboardCache.hasCachedData()) {
                    "Reconnecting to Tor network..."
                } else {
                    "Connecting to Tor network..."
                }
                _uiState.value = DashboardUiState.Error(message = message, isReconnecting = true)
            }
            return
        }

        viewModelScope.launch {
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
                handleError()
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
            if (!serverNeedsFallback) {
                _mempoolInfo.value = mempoolInfo
            }

            // Save state immediately with current mempool info
            DashboardCache.saveState(
                blockHeight = _blockHeight.value,
                blockTimestamp = _blockTimestamp.value,
                mempoolInfo = mempoolInfo,
                feeRates = _feeRates.value
            )

            // Check if mempool info needs fallback for fee distribution
            if (mempoolInfo != null && (mempoolInfo.needsHistogramFallback() || serverNeedsFallback)) {
                // Remember that this server needs fallback for future refreshes
                serverNeedsFallback = true
                
                viewModelScope.launch {
                    try {
                        val fallbackClient = NetworkClient.createTestClient(MempoolApi.BASE_URL)
                        val fallbackResponse = fallbackClient.getMempoolInfo()
                        if (fallbackResponse.isSuccessful && fallbackResponse.body() != null) {
                            val fallbackInfo = fallbackResponse.body()!!
                            if (!fallbackInfo.needsHistogramFallback()) {
                                // Update with size from current server but histogram from fallback
                                _mempoolInfo.value = mempoolInfo.copy(
                                    feeHistogram = fallbackInfo.feeHistogram,
                                    isUsingFallbackHistogram = true
                                )
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
                handleError()
            }
            _isMainRefreshing.value = false
        }
    }

    private fun handleError() {
        // If we have cached data, use it instead of showing an error
        if (DashboardCache.hasCachedData()) {
            val cachedState = DashboardCache.getCachedState()
            _blockHeight.value = cachedState.blockHeight
            _blockTimestamp.value = cachedState.blockTimestamp
            _feeRates.value = cachedState.feeRates
            _mempoolInfo.value = cachedState.mempoolInfo
            
            // Show appropriate message based on server type
            val message = if (NetworkClient.isUsingOnion()) {
                "Reconnecting to Tor network..."
            } else {
                "Fetching data..."
            }
            _uiState.value = DashboardUiState.Error(message = message, isReconnecting = true)
        } else {
            val message = "Connection failed. Check server settings."
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
    fun refreshMempoolInfo() {
        viewModelScope.launch {
            try {
                val mempoolInfoResponse = NetworkClient.mempoolApi.getMempoolInfo()
                if (mempoolInfoResponse.isSuccessful) {
                    val mempoolInfo = mempoolInfoResponse.body()

                    // If the server doesn't provide fee histogram, get it from mempool.space
                    if (mempoolInfo != null && mempoolInfo.feeHistogram.isEmpty()) {
                        val fallbackClient = NetworkClient.createTestClient(MempoolApi.BASE_URL)
                        val fallbackResponse = fallbackClient.getMempoolInfo()
                        if (fallbackResponse.isSuccessful && fallbackResponse.body() != null) {
                            val fallbackInfo = fallbackResponse.body()!!
                            if (!fallbackInfo.feeHistogram.isEmpty()) {
                                _mempoolInfo.value = mempoolInfo.copy(
                                    feeHistogram = fallbackInfo.feeHistogram,
                                    isUsingFallbackHistogram = true
                                )
                                return@launch
                            }
                        }
                    }

                    _mempoolInfo.value = mempoolInfo
                }
            } catch (e: Exception) {
                println("Error fetching mempool info: ${e.message}")
            }
        }
    }
}