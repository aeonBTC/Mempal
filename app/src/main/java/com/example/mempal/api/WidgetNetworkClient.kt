package com.example.mempal.api

import android.content.Context
import com.example.mempal.repository.SettingsRepository
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object WidgetNetworkClient {
    private const val TIMEOUT_SECONDS = 10L
    private const val DEFAULT_API_URL = "https://mempool.space"
    private var retrofit: Retrofit? = null
    private var mempoolApi: MempoolApi? = null

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    fun getMempoolApi(context: Context): MempoolApi {
        // Check if we have a valid API instance
        mempoolApi?.let { api ->
            if (!hasUrlChanged(context)) {
                return api
            }
        }

        // Create new API instance
        return createMempoolApi(context).also { 
            mempoolApi = it 
        }
    }

    private fun hasUrlChanged(context: Context): Boolean {
        val settingsRepository = SettingsRepository.getInstance(context)
        val currentUrl = settingsRepository.getApiUrl()
        return retrofit?.baseUrl()?.toString()?.contains(currentUrl) != true
    }

    private fun createMempoolApi(context: Context): MempoolApi {
        val settingsRepository = SettingsRepository.getInstance(context)
        val userApiUrl = settingsRepository.getApiUrl()

        // If the user's custom server is a .onion address, use the default mempool.space
        val baseUrl = if (userApiUrl.contains(".onion")) {
            DEFAULT_API_URL
        } else {
            userApiUrl
        }.let { url ->
            if (!url.endsWith("/")) "$url/" else url
        }

        val clientBuilder = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                var attempt = 0
                var response = chain.proceed(chain.request())
                while (!response.isSuccessful && attempt < 2) {
                    attempt++
                    response.close()
                    response = chain.proceed(chain.request())
                }
                response
            }

        val gson = GsonBuilder()
            .setLenient()
            .create()

        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

        return retrofit!!.create(MempoolApi::class.java)
    }
} 