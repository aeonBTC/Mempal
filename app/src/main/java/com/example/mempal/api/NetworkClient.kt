package com.example.mempal.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.mempal.repository.SettingsRepository
import com.example.mempal.tor.TorManager
import com.example.mempal.tor.TorStatus
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

object NetworkClient {
    private const val TIMEOUT_SECONDS = 15L
    private const val TEST_TIMEOUT_SECONDS = 10L
    private const val ONION_TEST_TIMEOUT_SECONDS = 45L
    private const val TOR_TIMEOUT_SECONDS = 30L
    private var retrofit: Retrofit? = null
    private var contextRef: WeakReference<Context>? = null
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized
    private var coroutineScope: CoroutineScope? = null
    private var connectivityManager: ConnectivityManager? = null
    private val _isNetworkAvailable = MutableStateFlow(false)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable
    private var lastInitAttempt = 0L
    private val minInitRetryDelay = 1000L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            coroutineScope?.launch {
                _isNetworkAvailable.value = true
                
                // Only add delay for Tor connections
                val torManager = TorManager.getInstance()
                if (torManager.isTorEnabled()) {
                    val now = System.currentTimeMillis()
                    if (now - lastInitAttempt < minInitRetryDelay) {
                        delay(minInitRetryDelay)
                    }
                    lastInitAttempt = now
                    
                    if (torManager.torStatus.value == TorStatus.CONNECTED) {
                        setupRetrofit(true)
                        _isInitialized.value = true
                    }
                } else {
                    setupRetrofit(false)
                    _isInitialized.value = true
                }
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            _isNetworkAvailable.value = false
            _isInitialized.value = false
        }
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    lateinit var mempoolApi: MempoolApi
        private set

    fun initialize(context: Context) {
        println("Initializing NetworkClient...")
        contextRef = WeakReference(context.applicationContext)
        coroutineScope?.cancel() // Cancel any existing scope
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        
        // Setup connectivity monitoring
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager?.registerNetworkCallback(networkRequest, networkCallback)
        
        // Check initial network state
        _isNetworkAvailable.value = isNetworkCurrentlyAvailable()
        lastInitAttempt = System.currentTimeMillis()

        // Check if current API URL is an onion address and manage Tor accordingly
        val settingsRepository = SettingsRepository.getInstance(context)
        val currentApiUrl = settingsRepository.getApiUrl()
        val torManager = TorManager.getInstance()
        
        if (currentApiUrl.contains(".onion")) {
            if (!torManager.isTorEnabled()) {
                println("Onion address detected, enabling Tor")
                torManager.startTor(context)
            }
        } else if (torManager.isTorEnabled()) {
            println("Non-onion address detected, disabling Tor")
            torManager.stopTor(context)
        }

        coroutineScope?.launch {
            torManager.torStatus.collect { status ->
                println("Tor status changed: $status")
                if (status == TorStatus.CONNECTED || status == TorStatus.DISCONNECTED) {
                    // Add delay before reinitializing to prevent rapid retries
                    val now = System.currentTimeMillis()
                    if (now - lastInitAttempt < minInitRetryDelay) {
                        delay(minInitRetryDelay)
                    }
                    lastInitAttempt = now
                    
                    if (isNetworkCurrentlyAvailable()) {
                        println("Setting up Retrofit with useProxy=${status == TorStatus.CONNECTED}")
                        setupRetrofit(status == TorStatus.CONNECTED)
                        _isInitialized.value = true
                    } else {
                        _isInitialized.value = false
                    }
                } else {
                    _isInitialized.value = false
                }
            }
        }
    }

    private fun isNetworkCurrentlyAvailable(): Boolean {
        val cm = connectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun cleanup() {
        connectivityManager?.unregisterNetworkCallback(networkCallback)
        connectivityManager = null
        coroutineScope?.cancel()
        coroutineScope = null
        contextRef = null
        retrofit = null
        _isInitialized.value = false
        _isNetworkAvailable.value = false
    }

    private fun setupRetrofit(useProxy: Boolean) {
        val context = contextRef?.get() ?: throw IllegalStateException("Context not available")
        val baseUrl = SettingsRepository.getInstance(context).getApiUrl().let { url ->
            if (!url.endsWith("/")) "$url/" else url
        }

        println("Setting up Retrofit with baseUrl: $baseUrl")

        val isOnion = baseUrl.contains(".onion")
        val timeoutSeconds = when {
            useProxy && isOnion -> TOR_TIMEOUT_SECONDS
            else -> TIMEOUT_SECONDS
        }

        val clientBuilder = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
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

        if (useProxy && isOnion) {
            println("Setting up Tor proxy")
            clientBuilder.proxy(java.net.Proxy(
                java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress("127.0.0.1", 9050)
            ))
        }

        val gson = GsonBuilder()
            .setLenient()
            .create()

        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

        mempoolApi = retrofit!!.create(MempoolApi::class.java)
        println("Retrofit setup complete")
    }

    fun createTestClient(baseUrl: String, useTor: Boolean = false): MempoolApi {
        val clientBuilder = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(if (useTor && baseUrl.contains(".onion")) ONION_TEST_TIMEOUT_SECONDS else TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(if (useTor && baseUrl.contains(".onion")) ONION_TEST_TIMEOUT_SECONDS else TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(if (useTor && baseUrl.contains(".onion")) ONION_TEST_TIMEOUT_SECONDS else TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)

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

    fun isUsingOnion(): Boolean {
        return mempoolApi.toString().contains(".onion")
    }
}
