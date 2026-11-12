package com.example.mempal.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.mempal.BuildConfig
import com.example.mempal.repository.SettingsRepository
import com.example.mempal.tor.TorManager
import com.example.mempal.tor.TorStatus
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    // Use WeakReference to avoid memory leaks - allows GC to reclaim context if NetworkClient outlives Activity
    private var contextRef: WeakReference<Context>? = null
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized
    private var coroutineScope: CoroutineScope? = null
    private var torStatusCollectorJob: Job? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallbackRegistered = false
    private val _isNetworkAvailable = MutableStateFlow(false)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable
    private var lastInitAttempt = 0L

    private var _mempoolApi: MempoolApi? = null
    
    // Track last setup configuration to avoid unnecessary re-initialization
    private var lastSetupUseProxy: Boolean? = null
    private var lastSetupBaseUrl: String? = null
    
    // Cached fallback clients for mempool.space (reused instead of creating new ones each time)
    private var cachedClearnetFallbackApi: MempoolApi? = null
    private var cachedOnionFallbackApi: MempoolApi? = null
    
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
            setupForCurrentNetwork()
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            // Only treat this as a full outage if no other usable network remains.
            val cm = connectivityManager
            val active = cm?.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            val stillAvailable = active != null &&
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            _isNetworkAvailable.value = stillAvailable
            if (!stillAvailable) {
                _isInitialized.value = false
                _mempoolApi = null
                lastSetupUseProxy = null
                lastSetupBaseUrl = null
            }
        }
    }

    // Custom safe logging interceptor that only logs headers, never reads body to avoid crashes
    private val safeLoggingInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val startTime = System.currentTimeMillis()
        
        // Log request
        android.util.Log.d("OkHttp", "--> ${request.method} ${request.url}")
        request.headers.forEach { header ->
            android.util.Log.d("OkHttp", "${header.first}: ${header.second}")
        }
        android.util.Log.d("OkHttp", "--> END ${request.method}")
        
        // Proceed with request
        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            android.util.Log.e("OkHttp", "<-- HTTP FAILED: ${e.message}")
            throw e
        }
        
        // Log response (only headers, never body)
        val tookMs = System.currentTimeMillis() - startTime
        android.util.Log.d("OkHttp", "<-- ${response.code} ${response.message} ${response.request.url} (${tookMs}ms)")
        response.headers.forEach { header ->
            android.util.Log.d("OkHttp", "${header.first}: ${header.second}")
        }
        android.util.Log.d("OkHttp", "<-- END HTTP")
        
        response
    }

    @Synchronized
    fun initialize(context: Context) {
        if (_isInitialized.value) return  // Already initialized
        lastInitAttempt = System.currentTimeMillis()
        println("Initializing NetworkClient...")
        contextRef = WeakReference(context.applicationContext)

        // Reuse a live scope. Replacing it cancels pending setup work that may
        // have been queued by onAvailable, which can leave the client stuck.
        if (coroutineScope?.isActive != true) {
            val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
                println("NetworkClient coroutine exception: ${throwable.message}")
                _isInitialized.value = false
            }
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + exceptionHandler)
        }
        
        // Setup connectivity monitoring
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        if (!networkCallbackRegistered) {
            connectivityManager?.registerNetworkCallback(networkRequest, networkCallback)
            networkCallbackRegistered = true
        }
        
        // Check initial network state
        _isNetworkAvailable.value = isNetworkCurrentlyAvailable()

        // Check if current API URL is an onion address and manage Tor accordingly
        val settingsRepository = SettingsRepository.getInstance(context)
        val currentApiUrl = settingsRepository.getApiUrl()
        val torManager = TorManager.getInstance()
        
        if (!currentApiUrl.contains(".onion") && torManager.isTorEnabled()) {
            println("Non-onion address detected, disabling Tor")
            torManager.stopTor(context)
        }
        // Onion URLs are started by MainActivity/NotificationService so a
        // background process start never attempts to start TorService.

        if (torStatusCollectorJob?.isActive != true) {
            torStatusCollectorJob = coroutineScope?.launch {
                try {
                    torManager.torStatus.collect { status ->
                        println("Tor status changed: $status")
                        val isTorEnabled = torManager.isTorEnabled()
                        
                        // Only process Tor status changes if Tor is actually enabled
                        // For non-Tor servers, ignore Tor status changes completely
                        if (!isTorEnabled) {
                            return@collect
                        }
                        
                        if (status == TorStatus.CONNECTED || status == TorStatus.DISCONNECTED) {
                            if (isNetworkCurrentlyAvailable()) {
                                println("Setting up Retrofit with useProxy=${status == TorStatus.CONNECTED}")
                                try {
                                    setupRetrofit(status == TorStatus.CONNECTED)
                                    _isInitialized.value = _mempoolApi != null
                                } catch (e: Exception) {
                                    println("Error setting up Retrofit: ${e.message}")
                                    // Don't set to false if we already have a working API
                                    if (_mempoolApi == null) {
                                        _isInitialized.value = false
                                    }
                                }
                            } else {
                                // Only set to false if we don't have a working API
                                if (_mempoolApi == null) {
                                    _isInitialized.value = false
                                }
                            }
                        } else {
                            // Tor is enabled but in CONNECTING or ERROR state
                            // Only set to false if we don't have a working API
                            if (_mempoolApi == null) {
                                _isInitialized.value = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("Error in Tor status collector: ${e.message}")
                    // Don't set to false if we already have a working API
                    if (_mempoolApi == null) {
                        _isInitialized.value = false
                    }
                }
            }
        }

        // Do not rely solely on onAvailable: it may have fired while the old
        // coroutine scope was being replaced. Setting up now is idempotent
        // because setupRetrofit skips an unchanged configuration.
        if (isNetworkCurrentlyAvailable()) {
            setupForCurrentNetwork()
        }
    }

    /**
     * Sets up Retrofit for the currently available network. Safe to call
     * repeatedly; [setupRetrofit] skips an unchanged configuration.
     */
    private fun setupForCurrentNetwork() {
        coroutineScope?.launch {
            try {
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
            } catch (e: Exception) {
                println("Error in network callback: ${e.message}")
                if (_mempoolApi == null) {
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
        if (networkCallbackRegistered) {
            try {
                connectivityManager?.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
                // Already unregistered
            }
            networkCallbackRegistered = false
        }
        connectivityManager = null
        torStatusCollectorJob?.cancel()
        torStatusCollectorJob = null
        coroutineScope?.cancel()
        coroutineScope = null
        contextRef = null
        retrofit = null
        _mempoolApi = null
        // Clear cached fallback clients
        cachedClearnetFallbackApi = null
        cachedOnionFallbackApi = null
        // Reset configuration tracking
        lastSetupUseProxy = null
        lastSetupBaseUrl = null
        _isInitialized.value = false
        _isNetworkAvailable.value = false
    }

    private fun setupRetrofit(useProxy: Boolean) {
        try {
            val context = contextRef?.get() ?: throw IllegalStateException("Context not available")
            val baseUrl = SettingsRepository.getInstance(context).getApiUrl().let { url ->
                if (!url.endsWith("/")) "$url/" else url
            }

            // A malformed URL would otherwise make Retrofit throw and leave the app
            // stuck waiting for initialization.
            val httpUrl = baseUrl.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Invalid API URL: $baseUrl")
            if (!NetworkPolicy.isCleartextAllowed(httpUrl)) {
                throw IllegalArgumentException(
                    "Plain HTTP is only allowed for .onion, localhost, or private networks"
                )
            }

            // Skip if already set up with the same configuration
            if (_mempoolApi != null && lastSetupUseProxy == useProxy && lastSetupBaseUrl == baseUrl) {
                println("Retrofit already set up with same configuration, skipping")
                return
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

            // Only add logging for debug builds in Tor (using safe wrapper to prevent crashes)
            if (BuildConfig.DEBUG && useProxy && isOnion) {
                clientBuilder.addInterceptor(safeLoggingInterceptor)
            }

            // Optimize retry strategy with proper exception handling
            clientBuilder.addInterceptor { chain ->
                val maxAttempts = if (useProxy && isOnion) 2 else 1
                var attempt = 0
                var lastException: Exception? = null
                var response: okhttp3.Response? = null
                
                while (attempt <= maxAttempts) {
                    try {
                        response?.close()
                        response = chain.proceed(chain.request())
                        if (response.isSuccessful) {
                            return@addInterceptor response
                        }
                        // Never retry client errors (including 429); let the caller back off.
                        if (response.code in 400..499) {
                            return@addInterceptor response
                        }
                        attempt++
                    } catch (e: java.net.SocketTimeoutException) {
                        lastException = e
                        attempt++
                        println("Connection timeout, attempt $attempt of ${maxAttempts + 1}")
                    } catch (e: java.io.IOException) {
                        lastException = e
                        attempt++
                        println("IO error, attempt $attempt of ${maxAttempts + 1}: ${e.message}")
                    }
                }
                
                // Return last response if we have one, otherwise throw the last exception
                response ?: throw (lastException ?: java.io.IOException("Failed to connect after $maxAttempts attempts"))
            }

            // Keep a host-level backoff when the server asks us to slow down.
            clientBuilder.addInterceptor(RateLimitInterceptor())

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

            val gson = GsonBuilder().create()

            retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(clientBuilder.build())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()

            _mempoolApi = retrofit!!.create(MempoolApi::class.java)
            
            // Save the configuration so we can skip unnecessary re-setup
            lastSetupUseProxy = useProxy
            lastSetupBaseUrl = baseUrl
            
            println("Retrofit setup complete")
        } catch (e: Exception) {
            println("Error setting up Retrofit: ${e.message}")
            _mempoolApi = null
            lastSetupUseProxy = null
            lastSetupBaseUrl = null
            _isInitialized.value = false
            throw e
        }
    }

    /**
     * Creates or returns a cached test client for the given URL.
     * All clients are cached by (baseUrl, useTor) pair to avoid creating
     * duplicate OkHttpClient/Retrofit instances.
     */
    fun createTestClient(baseUrl: String, useTor: Boolean = false): MempoolApi {
        val formattedUrl = if (!baseUrl.endsWith("/")) "$baseUrl/" else baseUrl

        val parsedUrl = formattedUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid API URL: $baseUrl")
        if (!NetworkPolicy.isCleartextAllowed(parsedUrl)) {
            throw IllegalArgumentException(
                "Plain HTTP is only allowed for .onion, localhost, or private networks"
            )
        }
        
        // Only the mempool.space fallback clients are cached. Arbitrary server
        // test clients are ephemeral: caching every URL typed in Settings would
        // grow without bound (each owns an OkHttp dispatcher/connection pool).
        val isMempoolSpaceClearnet = formattedUrl == MempoolApi.BASE_URL && !useTor
        val isMempoolSpaceOnion = formattedUrl == MempoolApi.ONION_BASE_URL && useTor
        
        if (isMempoolSpaceClearnet) {
            cachedClearnetFallbackApi?.let { return it }
        }
        if (isMempoolSpaceOnion) {
            cachedOnionFallbackApi?.let { return it }
        }
        
        // Build new client
        val isOnion = baseUrl.contains(".onion")
        val timeoutSeconds = if (useTor && isOnion) ONION_TEST_TIMEOUT_SECONDS else TEST_TIMEOUT_SECONDS
        
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        
        // Don't add logging for test clients to avoid body stream issues
        // Test clients are temporary and don't need logging

        // Respect rate limiting on fallback/custom servers too.
        clientBuilder.addInterceptor(RateLimitInterceptor())

        if (useTor && isOnion) {
            clientBuilder.proxy(java.net.Proxy(
                java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress("127.0.0.1", 9050)
            ))
        }

        val testRetrofit = Retrofit.Builder()
            .baseUrl(formattedUrl)
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val api = testRetrofit.create(MempoolApi::class.java)
        
        // Cache only the default mempool.space fallback clients.
        if (isMempoolSpaceClearnet) {
            cachedClearnetFallbackApi = api
        } else if (isMempoolSpaceOnion) {
            cachedOnionFallbackApi = api
        }
        
        return api
    }

    fun isUsingOnion(): Boolean {
        return contextRef?.get()?.let { context ->
            SettingsRepository.getInstance(context).getApiUrl().contains(".onion")
        } == true
    }
}