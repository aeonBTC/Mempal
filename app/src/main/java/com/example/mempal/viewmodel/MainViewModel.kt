package com.example.mempal.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mempal.api.FeeRates
import com.example.mempal.api.MempoolInfo
import com.example.mempal.api.NetworkClient
import com.example.mempal.api.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.Response
import java.io.IOException

class MainViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<Result<Unit>>(Result.Success(Unit))
    val uiState = _uiState.asStateFlow()

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

    init {
        viewModelScope.launch {
            NetworkClient.isInitialized.collect { initialized ->
                if (initialized) {
                    refreshData()
                }
            }
        }
    }

    fun refreshData() {
        if (!NetworkClient.isInitialized.value) {
            println("NetworkClient not initialized, skipping refresh")
            return
        }

        viewModelScope.launch {
            _uiState.value = Result.Loading
            try {
                println("Starting API calls...")
                
                val blockHeightResponse = NetworkClient.mempoolApi.getBlockHeight()
                println("Block height response: ${blockHeightResponse.code()}")
                
                val feeRatesResponse = NetworkClient.mempoolApi.getFeeRates()
                println("Fee rates response: ${feeRatesResponse.code()}")
                
                val mempoolInfoResponse = NetworkClient.mempoolApi.getMempoolInfo()
                println("Mempool info response: ${mempoolInfoResponse.code()}")
                
                // Get block timestamp
                val blockHashResponse = NetworkClient.mempoolApi.getLatestBlockHash()
                println("Block hash response: ${blockHashResponse.code()}")
                
                var timestamp: Long? = null
                if (blockHashResponse.isSuccessful) {
                    val hash = blockHashResponse.body()
                    if (hash != null) {
                        val blockInfoResponse = NetworkClient.mempoolApi.getBlockInfo(hash)
                        println("Block info response: ${blockInfoResponse.code()}")
                        if (blockInfoResponse.isSuccessful) {
                            timestamp = blockInfoResponse.body()?.timestamp
                        } else {
                            println("Failed to get block info: ${blockInfoResponse.errorBody()?.string()}")
                        }
                    } else {
                        println("Block hash response body was null")
                    }
                } else {
                    println("Failed to get block hash: ${blockHashResponse.errorBody()?.string()}")
                }

                processResponses(blockHeightResponse, feeRatesResponse, mempoolInfoResponse, timestamp)
            } catch (e: Exception) {
                println("Error refreshing data: ${e.message}")
                e.printStackTrace()
                handleError(e)
            }
        }
    }

    private fun processResponses(
        blockHeight: Response<Int>,
        feeRates: Response<FeeRates>,
        mempoolInfo: Response<MempoolInfo>,
        timestamp: Long?
    ) {
        // Update each value independently
        if (blockHeight.isSuccessful) {
            _blockHeight.value = blockHeight.body()
        }
        if (feeRates.isSuccessful) {
            _feeRates.value = feeRates.body()
        }
        if (mempoolInfo.isSuccessful) {
            _mempoolInfo.value = mempoolInfo.body()
        }
        _blockTimestamp.value = timestamp

        // Set hasInitialData if we have at least one successful response
        if (blockHeight.isSuccessful || feeRates.isSuccessful || mempoolInfo.isSuccessful) {
            hasInitialData = true
            _uiState.value = Result.Success(Unit)
        } else {
            _uiState.value = Result.Error("Server error. Please try again.")
        }

        // Log any failures for debugging
        if (!blockHeight.isSuccessful) {
            println("Block height request failed: ${blockHeight.code()} - ${blockHeight.errorBody()?.string()}")
        }
        if (!feeRates.isSuccessful) {
            println("Fee rates request failed: ${feeRates.code()} - ${feeRates.errorBody()?.string()}")
        }
        if (!mempoolInfo.isSuccessful) {
            println("Mempool info request failed: ${mempoolInfo.code()} - ${mempoolInfo.errorBody()?.string()}")
        }
    }

    private fun handleError(e: Throwable) {
        _uiState.value = Result.Error(
            when (e) {
                is IOException -> "Network error. Please check your connection."
                else -> "An unexpected error occurred: ${e.message}"
            }
        )
    }
}