package com.example.mempal.api

import retrofit2.Response
import retrofit2.http.GET

interface MempoolApi {
    @GET("api/blocks/tip/height")
    suspend fun getBlockHeight(): Response<Int>

    @GET("api/v1/fees/recommended")
    suspend fun getFeeRates(): Response<FeeRates>

    @GET("api/mempool")
    suspend fun getMempoolInfo(): Response<MempoolInfo>

    companion object {
        const val BASE_URL = "https://mempool.space/"
    }
}