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
    private const val TIMEOUT_SECONDS = 30L
    private var retrofit: Retrofit? = null
    private var contextRef: WeakReference<Context>? = null
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized
    private var coroutineScope: CoroutineScope? = null
    private var connectivityManager: ConnectivityManager? = null
    private val _isNetworkAvailable = MutableStateFlow(false)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            coroutineScope?.launch {
                _isNetworkAvailable.value = true
                if (_isInitialized.value) {
                    // Reinitialize the client when network becomes available
                    setupRetrofit(TorManager.getInstance().torStatus.value == TorStatus.CONNECTED)
                }
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            _isNetworkAvailable.value = false
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

        val torManager = TorManager.getInstance()

        coroutineScope?.launch {
            torManager.torStatus.collect { status ->
                println("Tor status changed: $status")
                if (status == TorStatus.CONNECTED || status == TorStatus.DISCONNECTED) {
                    println("Setting up Retrofit with useProxy=${status == TorStatus.CONNECTED}")
                    if (isNetworkCurrentlyAvailable()) {
                        setupRetrofit(status == TorStatus.CONNECTED)
                        _isInitialized.value = true
                        println("NetworkClient initialization complete")
                    }
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

        val clientBuilder = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (useProxy && baseUrl.contains(".onion")) {
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