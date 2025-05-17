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
    private const val TIMEOUT_SECONDS = 10L
    private const val TEST_TIMEOUT_SECONDS = 10L
    private const val ONION_TEST_TIMEOUT_SECONDS = 60L
    private const val TOR_TIMEOUT_SECONDS = 60L
    private const val MAX_PARALLEL_CONNECTIONS = 5
    private const val TOR_MAX_IDLE_CONNECTIONS = 5

    private var retrofit: Retrofit? = null
    private var contextRef: WeakReference<Context>? = null
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized
    private var coroutineScope: CoroutineScope? = null
    private var connectivityManager: ConnectivityManager? = null
    private val _isNetworkAvailable = MutableStateFlow(false)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable
    private var lastInitAttempt = 0L

    private var _mempoolApi: MempoolApi? = null
    val mempoolApi: MempoolApi
        get() {
            if (!_isInitialized.value) {
                contextRef?.get()?.let { context ->
                    // Try to re-initialize if we have a context
                    if (System.currentTimeMillis() - lastInitAttempt > 1000) {  // Prevent spam
                        initialize(context)
                    }
                }
                // Return null API if not initialized, let caller handle it
                return _mempoolApi ?: throw IllegalStateException("NetworkClient not initialized. Please ensure initialize() is called first.")
            }
            return _mempoolApi ?: throw IllegalStateException("NetworkClient not initialized. Please ensure initialize() is called first.")
        }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            coroutineScope?.launch {
                _isNetworkAvailable.value = true
                
                val torManager = TorManager.getInstance()
                if (torManager.isTorEnabled()) {
                    if (torManager.torStatus.value == TorStatus.CONNECTED) {
                        setupRetrofit(true)
                        _isInitialized.value = _mempoolApi != null
                    }
                } else {
                    setupRetrofit(false)
                    _isInitialized.value = _mempoolApi != null
                }
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            _isNetworkAvailable.value = false
            _isInitialized.value = false
            _mempoolApi = null
        }
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    @Synchronized
    fun initialize(context: Context) {
        if (_isInitialized.value) return  // Already initialized
        lastInitAttempt = System.currentTimeMillis()
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
                    if (isNetworkCurrentlyAvailable()) {
                        println("Setting up Retrofit with useProxy=${status == TorStatus.CONNECTED}")
                        setupRetrofit(status == TorStatus.CONNECTED)
                        _isInitialized.value = _mempoolApi != null
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
        _mempoolApi = null
        _isInitialized.value = false
        _isNetworkAvailable.value = false
        loggingInterceptor.level = HttpLoggingInterceptor.Level.BODY
    }

    private fun setupRetrofit(useProxy: Boolean) {
        try {
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

            val dispatcher = okhttp3.Dispatcher().apply {
                maxRequests = MAX_PARALLEL_CONNECTIONS
                maxRequestsPerHost = MAX_PARALLEL_CONNECTIONS
            }

            val clientBuilder = OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)

            // Only add logging for debug purposes in Tor
            if (useProxy && isOnion) {
                clientBuilder.addInterceptor(loggingInterceptor)
            }

            // Optimize retry strategy
            clientBuilder.addInterceptor { chain ->
                val maxAttempts = if (useProxy && isOnion) 2 else 1
                var attempt = 0
                var response = chain.proceed(chain.request())
                
                while (!response.isSuccessful && attempt < maxAttempts) {
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
                
                // Optimize connection pool for Tor
                clientBuilder.connectionPool(
                    okhttp3.ConnectionPool(
                        TOR_MAX_IDLE_CONNECTIONS,
                        30,
                        TimeUnit.SECONDS
                    )
                )
            } else {
                // Optimize connection pool for regular connections
                clientBuilder.connectionPool(
                    okhttp3.ConnectionPool(
                        MAX_PARALLEL_CONNECTIONS,
                        30,
                        TimeUnit.SECONDS
                    )
                )
            }

            val gson = GsonBuilder()
                .setLenient()
                .create()

            retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(clientBuilder.build())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()

            _mempoolApi = retrofit!!.create(MempoolApi::class.java)
            println("Retrofit setup complete")
        } catch (e: Exception) {
            println("Error setting up Retrofit: ${e.message}")
            _mempoolApi = null
            _isInitialized.value = false
            throw e
        }
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
        return contextRef?.get()?.let { context ->
            SettingsRepository.getInstance(context).getApiUrl().contains(".onion")
        } == true
    }
}