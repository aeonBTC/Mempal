package com.example.mempal.api

import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import com.example.mempal.repository.SettingsRepository
import com.example.mempal.tor.TorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {
    private const val TIMEOUT_SECONDS = 30L
    private var retrofit: Retrofit? = null
    private var context: Context? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    fun initialize(context: Context) {
        this.context = context.applicationContext
        val torManager = TorManager.getInstance()

        coroutineScope.launch {
            torManager.proxyReady.collect { proxyReady ->
                setupRetrofit(proxyReady)
            }
        }
    }

    private fun setupRetrofit(useProxy: Boolean) {
        val baseUrl = context?.let { ctx ->
            SettingsRepository.getInstance(ctx).getApiUrl().let { url ->
                if (!url.endsWith("/")) "$url/" else url
            }
        } ?: throw IllegalStateException("Context not initialized")

        val clientBuilder = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (useProxy && baseUrl.contains(".onion")) {
            clientBuilder.proxy(java.net.Proxy(
                java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress("127.0.0.1", 9050)
            ))
        }

        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val mempoolApi: MempoolApi
        get() = retrofit?.create(MempoolApi::class.java)
            ?: throw IllegalStateException("NetworkClient must be initialized first")
} 