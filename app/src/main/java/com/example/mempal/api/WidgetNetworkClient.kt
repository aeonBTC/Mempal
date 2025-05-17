package com.example.mempal.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
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
    private var isNetworkAvailable = false

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    fun getMempoolApi(context: Context): MempoolApi {
        // Update network status
        updateNetworkStatus(context)
        
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

    /**
     * Check if network connection is available
     * @param context Application context
     * @return true if network is available, false otherwise
     */
    fun isNetworkAvailable(context: Context): Boolean {
        updateNetworkStatus(context)
        return isNetworkAvailable
    }

    /**
     * Reset all cached network status and API instances.
     * This is useful when network connectivity is restored
     * to ensure fresh connections are established.
     */
    fun resetCache() {
        // Reset network state
        isNetworkAvailable = false
        
        // Reset API instance to force recreation
        mempoolApi = null
        retrofit = null
        
        Log.d("WidgetNetworkClient", "Network cache reset")
    }

    private fun updateNetworkStatus(context: Context) {
        try {
            val connectivityManager = 
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            
            val network = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(network)
            
            isNetworkAvailable = capabilities != null && 
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || 
                 capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                 capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                 capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                 capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            e.printStackTrace()
            isNetworkAvailable = false
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
                // Check network availability before proceeding
                if (!isNetworkAvailable) {
                    throw java.io.IOException("No network connection available")
                }
                
                try {
                    var attempt = 0
                    var response = chain.proceed(chain.request())
                    while (!response.isSuccessful && attempt < 2) {
                        attempt++
                        response.close()
                        response = chain.proceed(chain.request())
                    }
                    response
                } catch (e: java.net.UnknownHostException) {
                    // Convert DNS resolution failures to a standard IOException
                    // This prevents crashes and allows proper error handling
                    isNetworkAvailable = false // Update our network state
                    throw java.io.IOException("Unable to access network: DNS resolution failed", e)
                } catch (e: java.net.SocketTimeoutException) {
                    // Also handle timeout exceptions similarly
                    throw java.io.IOException("Network connection timed out", e)
                }
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