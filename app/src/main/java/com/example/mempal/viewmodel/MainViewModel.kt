package com.example.mempal.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mempal.api.*
import com.example.mempal.cache.DashboardCache
import com.example.mempal.tor.TorManager
import com.example.mempal.tor.TorStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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
    private val _blockHeight = MutableLiveData<Int?>()
    val blockHeight: LiveData<Int?> = _blockHeight

    private val _blockTimestamp = MutableLiveData<Long?>()
    val blockTimestamp: LiveData<Long?> = _blockTimestamp

    private val _feeRates = MutableLiveData<FeeRates?>()
    val feeRates: LiveData<FeeRates?> = _feeRates

    private val _mempoolInfo = MutableLiveData<MempoolInfo?>()
    val mempoolInfo: LiveData<MempoolInfo?> = _mempoolInfo

    private val _hashrateInfo = MutableLiveData<HashrateInfo>()
    val hashrateInfo: LiveData<HashrateInfo> = _hashrateInfo

    private val _difficultyAdjustment = MutableLiveData<DifficultyAdjustment>()
    val difficultyAdjustment: LiveData<DifficultyAdjustment> = _difficultyAdjustment

    private val _isMainRefreshing = MutableStateFlow(false)
    val isMainRefreshing: StateFlow<Boolean> = _isMainRefreshing

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState

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
                    "Connecting to Tor network..."
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
                    val torManager = TorManager.getInstance()
                    if (!torManager.isTorEnabled()) {
                        // If not using Tor, refresh immediately
                        _uiState.value = DashboardUiState.Loading
                        refreshDashboardData()
                        dashboardLoaded = true
                    } else if (torManager.torStatus.value == TorStatus.CONNECTED && !dashboardLoaded) {
                        // If Tor is already connected and we haven't loaded data yet, refresh
                        _uiState.value = DashboardUiState.Loading
                        refreshDashboardData()
                        dashboardLoaded = true
                    }
                    // If Tor is enabled but not connected, wait for Tor status changes
                } else {
                    // Reset dashboard loaded state when network is not initialized
                    dashboardLoaded = false
                }
            }
        }

        // Monitor Tor status changes
        viewModelScope.launch {
            TorManager.getInstance().torStatus.collect { status ->
                when (status) {
                    TorStatus.CONNECTING -> {
                        _uiState.value = DashboardUiState.Error(
                            message = "Connecting to Tor network...",
                            isReconnecting = true
                        )
                    }
                    TorStatus.ERROR -> {
                        _uiState.value = DashboardUiState.Error(
                            message = "Connection failed. Check server settings.",
                            isReconnecting = false
                        )
                    }
                    TorStatus.CONNECTED -> {
                        if (NetworkClient.isInitialized.value && !dashboardLoaded) {
                            _uiState.value = DashboardUiState.Loading
                            refreshDashboardData()
                            dashboardLoaded = true
                        }
                    }
                    else -> { /* No action needed for other states */ }
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
                    "Connecting to Tor network..."
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
                
                // Safely get the API instance
                val api = try {
                    NetworkClient.mempoolApi
                } catch (e: IllegalStateException) {
                    println("NetworkClient not properly initialized: ${e.message}")
                    _uiState.value = DashboardUiState.Error(
                        message = "Initializing connection...",
                        isReconnecting = true
                    )
                    return@launch
                }

                coroutineScope {
                    // For non-Tor connections, we'll use a more aggressive timeout
                    val timeoutMillis = if (NetworkClient.isUsingOnion()) 30000L else 5000L
                    val isUsingTor = NetworkClient.isUsingOnion()
                    
                    // For non-Tor connections, prioritize essential data first
                    if (!isUsingTor) {
                        // First batch: Essential data
                        val blockHeightDeferred = async { 
                            withTimeout(timeoutMillis) {
                                api.getBlockHeight()
                            }
                        }
                        val feeRatesDeferred = async { 
                            withTimeout(timeoutMillis) {
                                api.getFeeRates()
                            }
                        }

                        // Process essential data first
                        var hasAnySuccessfulResponse = false
                        
                        try {
                            val blockHeightResponse = blockHeightDeferred.await()
                            if (blockHeightResponse.isSuccessful) {
                                blockHeightResponse.body()?.let { blockHeight ->
                                    _blockHeight.value = blockHeight
                                    hasAnySuccessfulResponse = true
                                }
                            }
                        } catch (e: Exception) {
                            println("Error fetching block height: ${e.message}")
                        }

                        try {
                            val feeRatesResponse = feeRatesDeferred.await()
                            if (feeRatesResponse.isSuccessful) {
                                feeRatesResponse.body()?.let { feeRates ->
                                    _feeRates.value = feeRates
                                    hasAnySuccessfulResponse = true
                                }
                            }
                        } catch (e: Exception) {
                            println("Error fetching fee rates: ${e.message}")
                        }

                        // If we have essential data, show it immediately
                        if (hasAnySuccessfulResponse) {
                            _uiState.value = DashboardUiState.Success(isCache = false)
                            hasInitialData = true
                            
                            // Save state to cache
                            DashboardCache.saveState(
                                blockHeight = _blockHeight.value,
                                blockTimestamp = _blockTimestamp.value,
                                mempoolInfo = _mempoolInfo.value,
                                feeRates = _feeRates.value
                            )
                        }

                        // Second batch: Non-essential data
                        launch {
                            try {
                                val mempoolInfoResponse = withTimeout(timeoutMillis) {
                                    api.getMempoolInfo()
                                }
                                if (mempoolInfoResponse.isSuccessful) {
                                    val mempoolInfo = mempoolInfoResponse.body()
                                    if (mempoolInfo != null) {
                                        _mempoolInfo.value = mempoolInfo
                                        
                                        // Only attempt fallback if needed
                                        if (mempoolInfo.needsHistogramFallback() || serverNeedsFallback) {
                                            serverNeedsFallback = true
                                            try {
                                                val torManager = TorManager.getInstance()
                                                val isUsingTor = torManager.isTorEnabled() && torManager.torStatus.value == TorStatus.CONNECTED
                                                withTimeout(if (isUsingTor) 30000L else 5000L) {
                                                    val fallbackUrl = if (isUsingTor) {
                                                        MempoolApi.ONION_BASE_URL
                                                    } else {
                                                        MempoolApi.BASE_URL
                                                    }
                                                    val fallbackClient = NetworkClient.createTestClient(
                                                        baseUrl = fallbackUrl,
                                                        useTor = isUsingTor
                                                    )
                                                    val fallbackResponse = fallbackClient.getMempoolInfo()
                                                    if (fallbackResponse.isSuccessful && fallbackResponse.body() != null) {
                                                        val fallbackInfo = fallbackResponse.body()!!
                                                        if (!fallbackInfo.needsHistogramFallback()) {
                                                            _mempoolInfo.value = mempoolInfo.copy(
                                                                feeHistogram = fallbackInfo.feeHistogram,
                                                                isUsingFallbackHistogram = true
                                                            )
                                                            
                                                            // Save updated state to cache
                                                            DashboardCache.saveState(
                                                                blockHeight = _blockHeight.value,
                                                                blockTimestamp = _blockTimestamp.value,
                                                                mempoolInfo = _mempoolInfo.value,
                                                                feeRates = _feeRates.value
                                                            )
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                println("Non-critical error fetching fallback data: ${e.message}")
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                println("Non-critical error fetching mempool info: ${e.message}")
                            }
                        }

                        // Third batch: Additional data
                        if (_blockHeight.value != null) {
                            launch {
                                try {
                                    val blockHashResponse = withTimeout(timeoutMillis) {
                                        api.getLatestBlockHash()
                                    }
                                    if (blockHashResponse.isSuccessful) {
                                        blockHashResponse.body()?.let { hash ->
                                            val blockInfoResponse = withTimeout(timeoutMillis) {
                                                api.getBlockInfo(hash)
                                            }
                                            if (blockInfoResponse.isSuccessful) {
                                                blockInfoResponse.body()?.timestamp?.let { timestamp ->
                                                    _blockTimestamp.value = timestamp
                                                    
                                                    // Save updated state to cache with new timestamp
                                                    DashboardCache.saveState(
                                                        blockHeight = _blockHeight.value,
                                                        blockTimestamp = _blockTimestamp.value,
                                                        mempoolInfo = _mempoolInfo.value,
                                                        feeRates = _feeRates.value
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    println("Non-critical error fetching timestamp: ${e.message}")
                                }
                            }
                        }
                    } else {
                        // For Tor connections, use longer timeouts but similar approach
                        val blockHeightDeferred = async { 
                            withTimeout(timeoutMillis) {
                                api.getBlockHeight()
                            }
                        }
                        val feeRatesDeferred = async { 
                            withTimeout(timeoutMillis) {
                                api.getFeeRates()
                            }
                        }
                        val mempoolInfoDeferred = async {
                            withTimeout(timeoutMillis) {
                                api.getMempoolInfo()
                            }
                        }

                        // Process all responses
                        var hasAnySuccessfulResponse = false
                        
                        try {
                            val blockHeightResponse = blockHeightDeferred.await()
                            if (blockHeightResponse.isSuccessful) {
                                blockHeightResponse.body()?.let { blockHeight ->
                                    _blockHeight.value = blockHeight
                                    hasAnySuccessfulResponse = true
                                }
                            }
                        } catch (e: Exception) {
                            println("Error fetching block height: ${e.message}")
                        }

                        try {
                            val feeRatesResponse = feeRatesDeferred.await()
                            if (feeRatesResponse.isSuccessful) {
                                feeRatesResponse.body()?.let { feeRates ->
                                    _feeRates.value = feeRates
                                    hasAnySuccessfulResponse = true
                                }
                            }
                        } catch (e: Exception) {
                            println("Error fetching fee rates: ${e.message}")
                        }

                        try {
                            val mempoolInfoResponse = mempoolInfoDeferred.await()
                            if (mempoolInfoResponse.isSuccessful) {
                                mempoolInfoResponse.body()?.let { mempoolInfo ->
                                    _mempoolInfo.value = mempoolInfo
                                    hasAnySuccessfulResponse = true
                                }
                            }
                        } catch (e: Exception) {
                            println("Error fetching mempool info: ${e.message}")
                        }

                        // If we have any data, show it and cache it
                        if (hasAnySuccessfulResponse) {
                            _uiState.value = DashboardUiState.Success(isCache = false)
                            hasInitialData = true
                            
                            // Save state to cache
                            DashboardCache.saveState(
                                blockHeight = _blockHeight.value,
                                blockTimestamp = _blockTimestamp.value,
                                mempoolInfo = _mempoolInfo.value,
                                feeRates = _feeRates.value
                            )
                        }

                        // Get block timestamp if we have height
                        if (_blockHeight.value != null) {
                            launch {
                                try {
                                    val blockHashResponse = withTimeout(timeoutMillis) {
                                        api.getLatestBlockHash()
                                    }
                                    if (blockHashResponse.isSuccessful) {
                                        blockHashResponse.body()?.let { hash ->
                                            val blockInfoResponse = withTimeout(timeoutMillis) {
                                                api.getBlockInfo(hash)
                                            }
                                            if (blockInfoResponse.isSuccessful) {
                                                blockInfoResponse.body()?.timestamp?.let { timestamp ->
                                                    _blockTimestamp.value = timestamp
                                                    
                                                    // Save updated state to cache with new timestamp
                                                    DashboardCache.saveState(
                                                        blockHeight = _blockHeight.value,
                                                        blockTimestamp = _blockTimestamp.value,
                                                        mempoolInfo = _mempoolInfo.value,
                                                        feeRates = _feeRates.value
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    println("Non-critical error fetching timestamp: ${e.message}")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("Error refreshing data: ${e.message}")
                e.printStackTrace()
                handleError()
            } finally {
                _isMainRefreshing.value = false
            }
        }
    }

    // Updated refreshData function with type-safe handling
    fun refreshData() {
        viewModelScope.launch {
            _uiState.value = DashboardUiState.Loading
            _isMainRefreshing.value = true

            try {
                // Check if NetworkClient is initialized and Tor is ready if needed
                if (!NetworkClient.isInitialized.value) {
                    val torManager = TorManager.getInstance()
                    if (NetworkClient.isUsingOnion() && torManager.torStatus.value != TorStatus.CONNECTED) {
                        _uiState.value = DashboardUiState.Error("Waiting for Tor connection...", isReconnecting = true)
                        _isMainRefreshing.value = false
                        return@launch
                    }
                    _uiState.value = DashboardUiState.Error("Initializing network...", isReconnecting = true)
                    _isMainRefreshing.value = false
                    return@launch
                }

                // Create separate async calls with explicit types
                val blockHeightDeferred = async { NetworkClient.mempoolApi.getBlockHeight() }
                val mempoolInfoDeferred = async { NetworkClient.mempoolApi.getMempoolInfo() }
                val feeRatesDeferred = async { NetworkClient.mempoolApi.getFeeRates() }
                val hashrateInfoDeferred = async { NetworkClient.mempoolApi.getHashrateInfo() }
                val difficultyAdjustmentDeferred = async { NetworkClient.mempoolApi.getDifficultyAdjustment() }
                
                // Get block hash and info in parallel
                val blockHashDeferred = async { NetworkClient.mempoolApi.getLatestBlockHash() }
                val blockHashResponse = blockHashDeferred.await()
                val blockInfoDeferred = if (blockHashResponse.isSuccessful) {
                    blockHashResponse.body()?.let { hash ->
                        async { NetworkClient.mempoolApi.getBlockInfo(hash) }
                    }
                } else null

                // Await all responses with proper typing
                val blockHeightResponse = blockHeightDeferred.await()
                val mempoolInfoResponse = mempoolInfoDeferred.await()
                val feeRatesResponse = feeRatesDeferred.await()
                val hashrateInfoResponse = hashrateInfoDeferred.await()
                val difficultyAdjustmentResponse = difficultyAdjustmentDeferred.await()
                val blockInfoResponse = blockInfoDeferred?.await()

                if (blockHeightResponse.isSuccessful && 
                    mempoolInfoResponse.isSuccessful && 
                    feeRatesResponse.isSuccessful && 
                    hashrateInfoResponse.isSuccessful &&
                    difficultyAdjustmentResponse.isSuccessful) {
                    
                    // Process responses
                    blockHeightResponse.body()?.let { height -> _blockHeight.value = height }
                    
                    // Process block timestamp
                    blockInfoResponse?.let { response ->
                        if (response.isSuccessful) {
                            response.body()?.timestamp?.let { timestamp ->
                                _blockTimestamp.value = timestamp
                            }
                        }
                    }

                    feeRatesResponse.body()?.let { rates -> _feeRates.value = rates }
                    hashrateInfoResponse.body()?.let { info -> _hashrateInfo.value = info }
                    difficultyAdjustmentResponse.body()?.let { adjustment -> _difficultyAdjustment.value = adjustment }

                    // Handle mempool info with fallback logic
                    mempoolInfoResponse.body()?.let { mempoolInfo ->
                        _mempoolInfo.value = mempoolInfo
                        
                        // Check if we need fallback data
                        if (mempoolInfo.needsHistogramFallback() || serverNeedsFallback) {
                            serverNeedsFallback = true
                            try {
                                val torManager = TorManager.getInstance()
                                val isUsingTor = torManager.isTorEnabled() && torManager.torStatus.value == TorStatus.CONNECTED
                                withTimeout(if (isUsingTor) 30000L else 5000L) {
                                    val fallbackUrl = if (isUsingTor) {
                                        MempoolApi.ONION_BASE_URL
                                    } else {
                                        MempoolApi.BASE_URL
                                    }
                                    val fallbackClient = NetworkClient.createTestClient(
                                        baseUrl = fallbackUrl,
                                        useTor = isUsingTor
                                    )
                                    val fallbackResponse = fallbackClient.getMempoolInfo()
                                    if (fallbackResponse.isSuccessful && fallbackResponse.body() != null) {
                                        val fallbackInfo = fallbackResponse.body()!!
                                        if (!fallbackInfo.needsHistogramFallback()) {
                                            _mempoolInfo.value = mempoolInfo.copy(
                                                feeHistogram = fallbackInfo.feeHistogram,
                                                isUsingFallbackHistogram = true
                                            )
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                println("Non-critical error fetching fallback data: ${e.message}")
                            }
                        }
                    }

                    _uiState.value = DashboardUiState.Success(isCache = false)
                } else {
                    _uiState.value = DashboardUiState.Error("Failed to fetch data")
                }
            } catch (e: Exception) {
                _uiState.value = DashboardUiState.Error(e.localizedMessage ?: "Unknown error")
            } finally {
                _isMainRefreshing.value = false
            }
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
            
            // Show appropriate message based on connection state
            val message = when {
                !NetworkClient.isNetworkAvailable.value -> "Waiting for network connection..."
                NetworkClient.isUsingOnion() && TorManager.getInstance().torStatus.value == TorStatus.CONNECTING -> 
                    "Connecting to Tor network..."
                else -> "Connecting to server..."
            }
            _uiState.value = DashboardUiState.Error(message = message, isReconnecting = true)
        } else {
            val message = when {
                !NetworkClient.isNetworkAvailable.value -> "No network connection"
                NetworkClient.isUsingOnion() && TorManager.getInstance().torStatus.value != TorStatus.CONNECTED -> 
                    "Tor connection failed"
                else -> "Connection failed. Check server settings."
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
}