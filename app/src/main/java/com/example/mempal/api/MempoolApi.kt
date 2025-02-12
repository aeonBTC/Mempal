@file:Suppress("PropertyName")

package com.example.mempal.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

interface MempoolApi {
    @GET("api/blocks/tip/height")
    suspend fun getBlockHeight(): Response<Int>

    @GET("api/blocks/tip/hash")
    suspend fun getLatestBlockHash(): Response<String>

    @GET("api/block/{hash}")
    suspend fun getBlockInfo(@Path("hash") hash: String): Response<BlockInfo>

    @GET("api/v1/fees/recommended")
    suspend fun getFeeRates(): Response<FeeRates>

    @GET("api/mempool")
    suspend fun getMempoolInfo(): Response<MempoolInfo>

    @GET("api/tx/{txid}")
    suspend fun getTransaction(@Path("txid") txid: String): Response<TransactionResponse>

    @GET("api/v1/mining/hashrate/3d")
    suspend fun getHashrateInfo(): Response<HashrateInfo>

    @GET("api/v1/difficulty-adjustment")
    suspend fun getDifficultyAdjustment(): Response<DifficultyAdjustment>

    companion object {
        const val BASE_URL = "https://mempool.space/"
        const val ONION_BASE_URL = "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion/"
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

data class BlockInfo(
    val id: String,
    val height: Int,
    val timestamp: Long
)

data class DifficultyAdjustment(
    val progressPercent: Double,
    val difficultyChange: Double,
    val estimatedRetargetDate: Long,
    val remainingBlocks: Int,
    val remainingTime: Long,
    val previousRetarget: Double
)