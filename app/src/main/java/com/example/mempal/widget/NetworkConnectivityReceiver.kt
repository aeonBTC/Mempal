package com.example.mempal.widget

import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PowerManager
import android.util.Log
import com.example.mempal.api.WidgetNetworkClient

/**
 * BroadcastReceiver that monitors network connectivity changes and power save mode changes
 * and updates widgets when internet is restored or power save mode changes
 */
class NetworkConnectivityReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NetworkReceiver"
        @Volatile
        private var wasOffline = false
        @Volatile
        private var wasInPowerSaveMode = false
        @Volatile
        private var networkCallbackRegistered = false
        @Volatile
        private var powerSaveModeReceiverRegistered = false
        private var networkCallback: ConnectivityManager.NetworkCallback? = null
        private var powerSaveReceiver: BroadcastReceiver? = null
        private var connectivityManager: ConnectivityManager? = null
        private val lock = Any()

        /**
         * Register the network callback to monitor connectivity changes
         */
        fun registerNetworkCallback(context: Context) {
            synchronized(lock) {
                if (networkCallbackRegistered) return
                
                try {
                    connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    
                    networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        // When a network becomes available
                        Log.d(TAG, "Network available")
                        
                        // Check if internet is actually accessible
                        val cm = connectivityManager ?: return
                        val capabilities = cm.getNetworkCapabilities(network)
                        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                                          capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        
                        if (hasInternet && wasOffline) {
                            Log.d(TAG, "Internet restored, refreshing widgets")
                            wasOffline = false
                            
                            // Reset NetworkClient state
                            WidgetNetworkClient.resetCache()
                            
                            // Reset tap state
                            WidgetUtils.resetTapState()
                            
                            // Request immediate widget update
                            refreshAllWidgets(context)
                        }
                    }
                    
                    override fun onLost(network: Network) {
                        // When a network is lost
                        Log.d(TAG, "Network lost")
                        wasOffline = true
                    }
                }
                
                    val networkRequest = NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    
                    connectivityManager?.registerNetworkCallback(networkRequest, networkCallback!!)
                    networkCallbackRegistered = true
                    
                    // Initialize the wasOffline state based on current connectivity
                    wasOffline = !WidgetNetworkClient.isNetworkAvailable(context)
                    
                    Log.d(TAG, "Network callback registered, initial offline state: $wasOffline")
                    
                    // Also register power save mode receiver
                    registerPowerSaveModeReceiver(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Error registering network callback", e)
                    networkCallback = null
                    connectivityManager = null
                }
            }
        }
        
        /**
         * Unregister the network callback and power save receiver
         * Should be called when all widgets are disabled
         */
        fun unregisterNetworkCallback(context: Context) {
            synchronized(lock) {
                try {
                    if (networkCallbackRegistered && networkCallback != null && connectivityManager != null) {
                        connectivityManager?.unregisterNetworkCallback(networkCallback!!)
                        networkCallback = null
                        networkCallbackRegistered = false
                        Log.d(TAG, "Network callback unregistered")
                    } else {
                        // No action needed if not registered
                    }
                    
                    if (powerSaveModeReceiverRegistered && powerSaveReceiver != null) {
                        context.applicationContext.unregisterReceiver(powerSaveReceiver!!)
                        powerSaveReceiver = null
                        powerSaveModeReceiverRegistered = false
                        Log.d(TAG, "Power save receiver unregistered")
                    } else {
                        // No action needed if not registered
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error unregistering network callback", e)
                }
            }
        }
        
        /**
         * Register a receiver for power save mode changes
         */
        private fun registerPowerSaveModeReceiver(context: Context) {
            synchronized(lock) {
                if (powerSaveModeReceiverRegistered) return
                
                try {
                    // Get current power save mode state
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    wasInPowerSaveMode = powerManager.isPowerSaveMode
                    
                    // Create and register the receiver
                    powerSaveReceiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (intent.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                                val powerManagerService = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                                val isInPowerSaveMode = powerManagerService.isPowerSaveMode
                                
                                if (isInPowerSaveMode != wasInPowerSaveMode) {
                                    Log.d(TAG, "Power save mode changed: $isInPowerSaveMode")
                                    wasInPowerSaveMode = isInPowerSaveMode
                                    
                                    // Reset tap state to fix any unresponsive widgets
                                    WidgetUtils.resetTapState()
                                    
                                    // Reset network client cache
                                    WidgetNetworkClient.resetCache()
                                    
                                    // Refresh widgets to adapt to new power mode
                                    refreshAllWidgets(context)
                                }
                            }
                        }
                    }
                
                    val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                    context.applicationContext.registerReceiver(powerSaveReceiver!!, filter)
                    powerSaveModeReceiverRegistered = true
                    
                    Log.d(TAG, "Power save mode receiver registered, initial state: $wasInPowerSaveMode")
                } catch (e: Exception) {
                    Log.e(TAG, "Error registering power save mode receiver", e)
                    powerSaveReceiver = null
                }
            }
        }
        
        /**
         * Refresh all widget types
         */
        private fun refreshAllWidgets(context: Context) {
            refreshWidgetType(context, BlockHeightWidget::class.java, BlockHeightWidget.REFRESH_ACTION)
            refreshWidgetType(context, MempoolSizeWidget::class.java, MempoolSizeWidget.REFRESH_ACTION)
            refreshWidgetType(context, FeeRatesWidget::class.java, FeeRatesWidget.REFRESH_ACTION)
            refreshWidgetType(context, CombinedStatsWidget::class.java, CombinedStatsWidget.REFRESH_ACTION)
        }
        
        /**
         * Refresh a specific widget type
         */
        private fun refreshWidgetType(context: Context, widgetClass: Class<out AppWidgetProvider>, refreshAction: String) {
            val intent = Intent(context, widgetClass).apply {
                action = refreshAction
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Handle connectivity changes
            @Suppress("DEPRECATION") // Used for backwards compatibility on older devices
            ConnectivityManager.CONNECTIVITY_ACTION -> {
                // Use our reliable connectivity check
                val hasNetwork = WidgetNetworkClient.isNetworkAvailable(context)
                
                if (hasNetwork && wasOffline) {
                    Log.d(TAG, "Internet restored (via broadcast), refreshing widgets")
                    wasOffline = false
                    
                    // Reset NetworkClient state
                    WidgetNetworkClient.resetCache()
                    
                    // Reset tap state
                    WidgetUtils.resetTapState()
                    
                    // Refresh all widgets
                    refreshAllWidgets(context)
                } else if (!hasNetwork) {
                    wasOffline = true
                }
            }
            
            // Handle power save mode changes
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                val powerManagerService = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val isInPowerSaveMode = powerManagerService.isPowerSaveMode
                
                if (isInPowerSaveMode != wasInPowerSaveMode) {
                    Log.d(TAG, "Power save mode changed (via broadcast): $isInPowerSaveMode")
                    wasInPowerSaveMode = isInPowerSaveMode
                    
                    // Reset tap state
                    WidgetUtils.resetTapState()
                    
                    // Reset network client
                    WidgetNetworkClient.resetCache()
                    
                    // Refresh widgets 
                    refreshAllWidgets(context)
                }
            }
        }
    }
} 