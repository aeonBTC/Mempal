package com.example.mempal.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

interface MempoolApi {
    @GET("api/blocks/tip/height")
    suspend fun getBlockHeight(): Response<Int>

    @GET("api/v1/fees/recommended")
    suspend fun getFeeRates(): Response<FeeRates>

    @GET("api/mempool")
    suspend fun getMempoolInfo(): Response<MempoolInfo>

    @GET("api/tx/{txid}")
    suspend fun getTransaction(@Path("txid") txid: String): Response<TransactionResponse>

    companion object {
        const val BASE_URL = "https://mempool.space/"
    }
}

data class TransactionResponse(
    val txid: String,
    val status: TransactionStatus
)

data class TransactionStatus(
    val confirmed: Boolean,
    val block_height: Int,
    val block_hash: String,
    val block_time: Long
)