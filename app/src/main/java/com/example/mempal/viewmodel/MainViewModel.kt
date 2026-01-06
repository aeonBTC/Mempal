package com.example.mempal.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mempal.api.DifficultyAdjustment
import com.example.mempal.api.FeeRates
import com.example.mempal.api.FeeRatesHelper
import com.example.mempal.api.HashrateInfo
import com.example.mempal.api.MempoolApi
import com.example.mempal.api.MempoolInfo
import com.example.mempal.api.NetworkClient
import com.example.mempal.cache.DashboardCache
import com.example.mempal.repository.SettingsRepository
import com.example.mempal.tor.TorManager
import com.example.mempal.tor.TorStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import retrofit2.Response
import java.lang.ref.WeakReference

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
    private var usePreciseFees = false
    // Use WeakReference to avoid memory leaks - allows GC to reclaim context if ViewModel outlives Activity
    private var contextRef: WeakReference<Context>? = null
    private var lastAutoRefreshTime: Long = 0L
    private val autoRefreshIntervalMs = 60_000L // 1 minute
    
    // Cached fallback API clients to avoid recreating on each refresh
    private var cachedClearnetFallbackApi: MempoolApi? = null
    private var cachedOnionFallbackApi: MempoolApi? = null

    fun setContext(context: Context) {
        contextRef = WeakReference(context.applicationContext)
    }

    fun setUsePreciseFees(enabled: Boolean) {
        usePreciseFees = enabled
    }

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
            try {
                NetworkClient.isInitialized.collect { initialized ->
                    if (initialized) {
                        val torManager = TorManager.getInstance()
                        if (!torManager.isTorEnabled()) {
                            // If not using Tor, refresh immediately
                            // Get current precise fees setting from SettingsRepository
                            val currentPreciseFees = contextRef?.get()?.let { 
                                SettingsRepository.getInstance(it).settings.value.usePreciseFees 
                            }
                            // Only refresh if we have context - MainActivity will handle it otherwise
                            if (currentPreciseFees != null) {
                                _uiState.value = DashboardUiState.Loading
                                setUsePreciseFees(currentPreciseFees)
                                refreshData(currentPreciseFees, isManualRefresh = false)
                                dashboardLoaded = true
                            }
                        } else if (torManager.torStatus.value == TorStatus.CONNECTED && !dashboardLoaded) {
                            // If Tor is already connected and we haven't loaded data yet, refresh
                            // Get current precise fees setting from SettingsRepository
                            val currentPreciseFees = contextRef?.get()?.let { 
                                SettingsRepository.getInstance(it).settings.value.usePreciseFees 
                            }
                            // Only refresh if we have context - MainActivity will handle it otherwise
                            if (currentPreciseFees != null) {
                                _uiState.value = DashboardUiState.Loading
                                setUsePreciseFees(currentPreciseFees)
                                refreshData(currentPreciseFees, isManualRefresh = false)
                                dashboardLoaded = true
                            }
                        }
                        // If Tor is enabled but not connected, wait for Tor status changes
                    } else {
                        // Reset dashboard loaded state when network is not initialized
                        dashboardLoaded = false
                    }
                }
            } catch (e: Exception) {
                println("Error in network initialization collector: ${e.message}")
                handleError()
            }
        }

        // Monitor Tor status changes
        viewModelScope.launch {
            try {
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
                                // Get current precise fees setting from SettingsRepository
                                val currentPreciseFees = contextRef?.get()?.let { 
                                    SettingsRepository.getInstance(it).settings.value.usePreciseFees 
                                }
                                // Only refresh if we have context - MainActivity will handle it otherwise
                                if (currentPreciseFees != null) {
                                    _uiState.value = DashboardUiState.Loading
                                    setUsePreciseFees(currentPreciseFees)
                                    refreshData(currentPreciseFees, isManualRefresh = false)
                                    dashboardLoaded = true
                                }
                            }
                        }
                        else -> { /* No action needed for other states */ }
                    }
                }
            } catch (e: Exception) {
                println("Error in Tor status collector: ${e.message}")
                handleError()
            }
        }
    }

    // Function to handle tab changes
    fun onTabSelected(tab: Int) {
        when (tab) {
            0 -> {
                // Only auto-refresh if dashboard not loaded OR if 1 minute has elapsed since last auto-refresh
                val shouldAutoRefresh = if (!dashboardLoaded) {
                    true
                } else {
                    val timeSinceLastRefresh = System.currentTimeMillis() - lastAutoRefreshTime
                    timeSinceLastRefresh >= autoRefreshIntervalMs
                }
                
                if (shouldAutoRefresh && NetworkClient.isInitialized.value) {
                    // Get current precise fees setting if context is available
                    val currentPreciseFees = contextRef?.get()?.let { 
                        SettingsRepository.getInstance(it).settings.value.usePreciseFees 
                    }
                    // Only refresh if we have context to get the correct setting
                    if (currentPreciseFees != null) {
                        setUsePreciseFees(currentPreciseFees)
                        refreshData(currentPreciseFees, isManualRefresh = false)
                        dashboardLoaded = true
                    }
                }
            }
        }
    }
    
    // Check if auto-refresh should happen (1 minute elapsed)
    fun shouldAutoRefresh(): Boolean {
        val timeSinceLastRefresh = System.currentTimeMillis() - lastAutoRefreshTime
        return timeSinceLastRefresh >= autoRefreshIntervalMs
    }

    // Helper extension to safely process API responses with consolidated error handling
    private inline fun <T> Response<T>?.processBody(
        onSuccess: (T) -> Unit,
        errorTag: String = "API"
    ): Boolean {
        if (this == null) return false
        return try {
            if (isSuccessful) {
                body()?.let { 
                    onSuccess(it)
                    true
                } ?: false
            } else false
        } catch (e: Exception) {
            println("Error processing $errorTag response: ${e.message}")
            try { raw().body?.close() } catch (_: Exception) {}
            false
        }
    }

    // Helper function to fetch histogram fallback data
    private fun fetchHistogramFallback(mempoolInfo: MempoolInfo) {
        viewModelScope.launch {
            try {
                val torManager = TorManager.getInstance()
                val isUsingTor = torManager.isTorEnabled() && torManager.torStatus.value == TorStatus.CONNECTED
                withTimeout(if (isUsingTor) 30000L else 5000L) {
                    // Use cached fallback client to avoid recreating on each refresh
                    val fallbackClient = if (isUsingTor) {
                        cachedOnionFallbackApi ?: NetworkClient.createTestClient(
                            baseUrl = MempoolApi.ONION_BASE_URL,
                            useTor = true
                        ).also { cachedOnionFallbackApi = it }
                    } else {
                        cachedClearnetFallbackApi ?: NetworkClient.createTestClient(
                            baseUrl = MempoolApi.BASE_URL,
                            useTor = false
                        ).also { cachedClearnetFallbackApi = it }
                    }
                    fallbackClient.getMempoolInfo().processBody(
                        onSuccess = { fallbackInfo ->
                            if (!fallbackInfo.needsHistogramFallback()) {
                                _mempoolInfo.value = mempoolInfo.copy(
                                    feeHistogram = fallbackInfo.feeHistogram,
                                    isUsingFallbackHistogram = true
                                )
                            }
                        },
                        errorTag = "fallback mempool info"
                    )
                }
            } catch (e: Exception) {
                println("Non-critical error fetching fallback data: ${e.message}")
            }
        }
    }

    // Helper function to get fee rates based on setting with fallback support
    private suspend fun getFeeRates(usePreciseFees: Boolean): Response<FeeRates>? {
        val context = contextRef?.get()
        val settingsRepo = context?.let { SettingsRepository.getInstance(it) }
        
        // Use shared helper if we have settings, otherwise fall back to simple call
        return try {
            if (settingsRepo != null) {
                FeeRatesHelper.getFeeRatesWithFallback(
                    api = NetworkClient.mempoolApi,
                    usePreciseFees = usePreciseFees,
                    settingsRepository = settingsRepo
                ) ?: try {
                    NetworkClient.mempoolApi.getFeeRates()
                } catch (e: Exception) {
                    println("Error in final getFeeRates fallback: ${e.message}")
                    null
                }
            } else {
                // No context available, just use regular fees
                if (usePreciseFees) {
                    try {
                        NetworkClient.mempoolApi.getPreciseFeeRates()
                    } catch (_: Exception) {
                        try {
                            NetworkClient.mempoolApi.getFeeRates()
                        } catch (e: Exception) {
                            println("Error in getFeeRates: ${e.message}")
                            null
                        }
                    }
                } else {
                    try {
                        NetworkClient.mempoolApi.getFeeRates()
                    } catch (e: Exception) {
                        println("Error in getFeeRates: ${e.message}")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            println("Error in getFeeRates: ${e.message}")
            null
        }
    }

    // Updated refreshData function with type-safe handling
    fun refreshData(usePreciseFees: Boolean? = null, isManualRefresh: Boolean = false) {
        // Prevent concurrent auto-refreshes - allow manual refresh to proceed
        // Check and set flag synchronously before launching coroutine to prevent race conditions
        if (_isMainRefreshing.value && !isManualRefresh) {
            return
        }
        
        // Set refreshing flag immediately (before coroutine launch) to block concurrent calls
        _isMainRefreshing.value = true
        
        // Update last auto-refresh time only if this is an auto-refresh
        if (!isManualRefresh) {
            lastAutoRefreshTime = System.currentTimeMillis()
        }
        viewModelScope.launch {
            _uiState.value = DashboardUiState.Loading

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

                // Use provided parameter or fallback to stored value
                val preciseFees = usePreciseFees ?: this@MainViewModel.usePreciseFees

                // Create separate async calls with explicit types - each wrapped in try-catch
                val blockHeightDeferred = async { 
                    try { NetworkClient.mempoolApi.getBlockHeight() } 
                    catch (e: Exception) { println("Error fetching block height: ${e.message}"); null }
                }
                val mempoolInfoDeferred = async { 
                    try { NetworkClient.mempoolApi.getMempoolInfo() } 
                    catch (e: Exception) { println("Error fetching mempool info: ${e.message}"); null }
                }
                val feeRatesDeferred = async { getFeeRates(preciseFees) }
                val hashrateInfoDeferred = async { 
                    try { NetworkClient.mempoolApi.getHashrateInfo() } 
                    catch (e: Exception) { println("Error fetching hashrate info: ${e.message}"); null }
                }
                val difficultyAdjustmentDeferred = async { 
                    try { NetworkClient.mempoolApi.getDifficultyAdjustment() } 
                    catch (e: Exception) { println("Error fetching difficulty adjustment: ${e.message}"); null }
                }
                
                // Get block hash and info in parallel
                val blockHashDeferred = async { 
                    try { NetworkClient.mempoolApi.getLatestBlockHash() } 
                    catch (e: Exception) { println("Error fetching block hash: ${e.message}"); null }
                }
                val blockHashResponse = blockHashDeferred.await()
                val blockInfoDeferred = if (blockHashResponse != null && blockHashResponse.isSuccessful) {
                    try {
                    blockHashResponse.body()?.let { hash ->
                        async { 
                            try { NetworkClient.mempoolApi.getBlockInfo(hash) }
                            catch (e: Exception) { println("Error fetching block info: ${e.message}"); null }
                        }
                        }
                    } catch (e: Exception) {
                        println("Error parsing block hash response: ${e.message}")
                        try {
                            blockHashResponse.raw().body?.close()
                        } catch (_: Exception) {}
                        null
                    }
                } else null

                // Await all responses with proper typing
                val blockHeightResponse = blockHeightDeferred.await()
                val mempoolInfoResponse = mempoolInfoDeferred.await()
                val feeRatesResponse = feeRatesDeferred.await()
                val hashrateInfoResponse = hashrateInfoDeferred.await()
                val difficultyAdjustmentResponse = difficultyAdjustmentDeferred.await()
                val blockInfoResponse = blockInfoDeferred?.await()

                // Process each response individually using consolidated error handling
                var hasAnySuccessfulResponse = false
                
                // Process block height
                if (blockHeightResponse.processBody(
                    onSuccess = { height -> _blockHeight.value = height },
                    errorTag = "block height"
                )) hasAnySuccessfulResponse = true
                
                // Process block timestamp
                blockInfoResponse.processBody(
                    onSuccess = { info -> _blockTimestamp.value = info.timestamp },
                    errorTag = "block info"
                )

                // Process fee rates
                if (feeRatesResponse.processBody(
                    onSuccess = { rates -> _feeRates.value = rates },
                    errorTag = "fee rates"
                )) hasAnySuccessfulResponse = true
                
                // Process hashrate info
                hashrateInfoResponse.processBody(
                    onSuccess = { info -> _hashrateInfo.value = info },
                    errorTag = "hashrate info"
                )
                
                // Process difficulty adjustment
                difficultyAdjustmentResponse.processBody(
                    onSuccess = { adjustment -> _difficultyAdjustment.value = adjustment },
                    errorTag = "difficulty adjustment"
                )

                // Handle mempool info with fallback logic
                if (mempoolInfoResponse.processBody(
                    onSuccess = { mempoolInfo ->
                        // Preserve fallback state if we've used fallback before
                        val preserveFallbackState = serverNeedsFallback && _mempoolInfo.value?.isUsingFallbackHistogram == true
                        _mempoolInfo.value = if (preserveFallbackState) {
                            mempoolInfo.copy(isUsingFallbackHistogram = true)
                        } else {
                            mempoolInfo
                        }
                        
                        // Check if we need fallback data (fire and forget - don't block)
                        if (mempoolInfo.needsHistogramFallback() || serverNeedsFallback) {
                            serverNeedsFallback = true
                            fetchHistogramFallback(mempoolInfo)
                        }
                    },
                    errorTag = "mempool info"
                )) hasAnySuccessfulResponse = true

                // If we have any successful response, show success
                if (hasAnySuccessfulResponse) {
                    _uiState.value = DashboardUiState.Success(isCache = false)
                    hasInitialData = true
                    // Cache will be written once at the end of refresh cycle
                } else {
                    // No data was successfully fetched - show error
                    handleError()
                }
            } catch (e: Exception) {
                println("Error in refreshData: ${e.message}")
                handleError()
            } finally {
                // Cache once at the end of refresh cycle with all available data
                if (hasInitialData || _blockHeight.value != null || _feeRates.value != null || 
                    _mempoolInfo.value != null) {
                    DashboardCache.saveState(
                        blockHeight = _blockHeight.value,
                        blockTimestamp = _blockTimestamp.value,
                        mempoolInfo = _mempoolInfo.value,
                        feeRates = _feeRates.value
                    )
                }
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
            val torManager = TorManager.getInstance()
            val message = when {
                !NetworkClient.isNetworkAvailable.value -> "Waiting for network connection..."
                NetworkClient.isUsingOnion() && torManager.torStatus.value == TorStatus.CONNECTING -> 
                    "Connecting to Tor network..."
                NetworkClient.isUsingOnion() && torManager.torStatus.value != TorStatus.CONNECTED -> 
                    "Tor not connected. Check server settings."
                else -> "Reconnecting to server..."
            }
            _uiState.value = DashboardUiState.Error(message = message, isReconnecting = true)
        } else {
            val torManager = TorManager.getInstance()
            val message = when {
                !NetworkClient.isNetworkAvailable.value -> "No network connection"
                NetworkClient.isUsingOnion() && torManager.torStatus.value != TorStatus.CONNECTED -> 
                    "Tor connection failed. Check server settings."
                !NetworkClient.isInitialized.value -> "Initializing connection..."
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