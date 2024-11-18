package com.example.mempal.api

import android.annotation.SuppressLint
import android.content.Context
import com.example.mempal.repository.SettingsRepository
import com.example.mempal.tor.TorManager
import com.example.mempal.tor.TorStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

@SuppressLint("StaticFieldLeak")
object NetworkClient {
    private const val TIMEOUT_SECONDS = 30L
    private var retrofit: Retrofit? = null
    private var context: Context? = null
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    lateinit var mempoolApi: MempoolApi
        private set

    fun initialize(context: Context) {
        this.context = context.applicationContext
        val torManager = TorManager.getInstance()

        coroutineScope.launch {
            torManager.torStatus.collect { status ->
                if (status == TorStatus.CONNECTED || status == TorStatus.DISCONNECTED) {
                    setupRetrofit(status == TorStatus.CONNECTED)
                    _isInitialized.value = true
                }
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

        mempoolApi = retrofit!!.create(MempoolApi::class.java)
    }

    fun createTestClient(baseUrl: String, useTor: Boolean = false): MempoolApi {
        val clientBuilder = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (useTor && baseUrl.contains(".onion")) {
            clientBuilder.proxy(java.net.Proxy(
                java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress("127.0.0.1", 9050)
            ))
        }

        val formattedUrl = if (!baseUrl.endsWith("/")) "$baseUrl/" else baseUrl

        val testRetrofit = Retrofit.Builder()
            .baseUrl(formattedUrl)
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return testRetrofit.create(MempoolApi::class.java)
    }

}