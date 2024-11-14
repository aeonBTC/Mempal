package com.example.mempal.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {
    private const val TIMEOUT_SECONDS = 30L

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder().apply {
        addInterceptor(loggingInterceptor)
        connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        retryOnConnectionFailure(true)
    }.build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(MempoolApi.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val mempoolApi: MempoolApi by lazy {
        retrofit.create(MempoolApi::class.java)
    }
} 