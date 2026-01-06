package com.example.mempal.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.mempal.repository.SettingsRepository
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object WidgetNetworkClient {
    private const val TIMEOUT_SECONDS = 10L
    private const val DEFAULT_API_URL = "https://mempool.space"
    private var retrofit: Retrofit? = null
    private var mempoolApi: MempoolApi? = null
    private var isNetworkAvailable = false

    // Custom safe logging interceptor that only logs headers, never reads body to avoid crashes
    private val safeLoggingInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val startTime = System.currentTimeMillis()
        
        // Log request
        Log.d("OkHttp", "--> ${request.method} ${request.url}")
        request.headers.forEach { header ->
            Log.d("OkHttp", "${header.first}: ${header.second}")
        }
        Log.d("OkHttp", "--> END ${request.method}")
        
        // Proceed with request
        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            Log.e("OkHttp", "<-- HTTP FAILED: ${e.message}")
            throw e
        }
        
        // Log response (only headers, never body)
        val tookMs = System.currentTimeMillis() - startTime
        Log.d("OkHttp", "<-- ${response.code} ${response.message} ${response.request.url} (${tookMs}ms)")
        response.headers.forEach { header ->
            Log.d("OkHttp", "${header.first}: ${header.second}")
        }
        Log.d("OkHttp", "<-- END HTTP")
        
        response
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
            .addInterceptor(safeLoggingInterceptor)
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
                    var lastException: Exception? = null
                    var response: okhttp3.Response? = null
                    
                    while (attempt < 3) {
                        try {
                            response?.close() // Close previous response if any
                            response = chain.proceed(chain.request())
                            
                            if (response.isSuccessful) {
                                return@addInterceptor response
                            }
                            
                            // If not successful, close and retry
                            response.close()
                            attempt++
                            
                            // Only retry on server errors (5xx), not client errors (4xx)
                            if (response.code < 500) {
                                break
                            }
                            
                            // Small delay before retry (we're still in the loop, so attempt < 3)
                            Thread.sleep(100 * attempt.toLong())
                        } catch (e: Exception) {
                            lastException = e
                            attempt++
                            
                            // Don't retry on certain exceptions or if we've exhausted attempts
                            if (e is java.net.UnknownHostException || 
                                e is java.net.ConnectException ||
                                attempt >= 3) {
                                throw e
                            }
                            
                            // Small delay before retry (we're still in the loop, so attempt < 3)
                            Thread.sleep(100 * attempt.toLong())
                        }
                    }
                    
                    // If we have a response, return it even if not successful
                    response ?: throw lastException ?: java.io.IOException("Network request failed")
                } catch (e: java.net.UnknownHostException) {
                    // Convert DNS resolution failures to a standard IOException
                    // This prevents crashes and allows proper error handling
                    isNetworkAvailable = false // Update our network state
                    throw java.io.IOException("Unable to access network: DNS resolution failed", e)
                } catch (e: java.net.SocketTimeoutException) {
                    // Also handle timeout exceptions similarly
                    throw java.io.IOException("Network connection timed out", e)
                } catch (e: java.net.ConnectException) {
                    isNetworkAvailable = false
                    throw java.io.IOException("Unable to connect to server", e)
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