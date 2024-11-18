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

    private val _blockHeight = MutableLiveData<Int>()
    val blockHeight: LiveData<Int> = _blockHeight

    private val _feeRates = MutableLiveData<FeeRates>()
    val feeRates: LiveData<FeeRates> = _feeRates

    private val _mempoolInfo = MutableLiveData<MempoolInfo>()
    val mempoolInfo: LiveData<MempoolInfo> = _mempoolInfo

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
        if (!NetworkClient.isInitialized.value) return

        viewModelScope.launch {
            _uiState.value = Result.Loading
            try {
                val blockHeightResponse = NetworkClient.mempoolApi.getBlockHeight()
                val feeRatesResponse = NetworkClient.mempoolApi.getFeeRates()
                val mempoolInfoResponse = NetworkClient.mempoolApi.getMempoolInfo()

                processResponses(blockHeightResponse, feeRatesResponse, mempoolInfoResponse)
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    private fun processResponses(
        blockHeight: Response<Int>,
        feeRates: Response<FeeRates>,
        mempoolInfo: Response<MempoolInfo>
    ) {
        if (blockHeight.isSuccessful && feeRates.isSuccessful && mempoolInfo.isSuccessful) {
            _blockHeight.value = blockHeight.body()
            _feeRates.value = feeRates.body()
            _mempoolInfo.value = mempoolInfo.body()
            _uiState.value = Result.Success(Unit)
            hasInitialData = true
        } else {
            _uiState.value = Result.Error("Server error. Please try again.")
        }
    }

    private fun handleError(e: Throwable) {
        _uiState.value = Result.Error(
            when (e) {
                is IOException -> "Network error. Please check your connection."
                else -> "An unexpected error occurred."
            }
        )
    }
}
