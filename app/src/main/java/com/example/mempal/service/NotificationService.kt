package com.example.mempal.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.mempal.R
import com.example.mempal.api.FeeRates
import com.example.mempal.api.FeeRatesHelper
import com.example.mempal.api.MempoolApi
import com.example.mempal.api.NetworkClient
import com.example.mempal.model.FeeRateType
import com.example.mempal.repository.SettingsRepository
import com.example.mempal.tor.TorManager
import com.example.mempal.tor.TorStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

class NotificationService : Service() {
    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "mempal_notifications"
        private const val TAG = "NotificationService"
        const val ACTION_STOP_SERVICE = "com.example.mempal.STOP_SERVICE"
        
        // Fixed notification IDs for tracking dismissal
        // New block notifications use dynamic IDs so they always show
        private const val NOTIFICATION_ID_FEE_RATE = 1001
        private const val NOTIFICATION_ID_MEMPOOL_SIZE = 1002
        private const val NOTIFICATION_ID_SPECIFIC_BLOCK = 1003
        private const val NOTIFICATION_ID_TX_CONFIRMATION = 1004

        @Volatile
        private var isRunningFlag = false

        fun isServiceRunning(): Boolean {
            return isRunningFlag
        }

        fun syncServiceState(context: Context) {
            val isRunning = isServiceRunning()
            val settingsRepository = SettingsRepository.getInstance(context)
            
            // Update settings if they don't match the actual service state
            if (settingsRepository.settings.value.isServiceEnabled != isRunning) {
                settingsRepository.updateSettings(
                    settingsRepository.settings.value.copy(isServiceEnabled = isRunning)
                )
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsRepository: SettingsRepository
    private var api: MempoolApi? = null
    private var lastBlockHeight: Int? = null
    private val monitoringJobs = mutableMapOf<String, Job>()
    private var lastCheckTimes = mutableMapOf<String, Long>()
    private var isForegroundStarted = false
    
    // Track previous settings to avoid unnecessary job restarts
    private var previousBlockSettings: BlockSettingsSnapshot? = null
    private var previousMempoolSettings: MempoolSettingsSnapshot? = null
    private var previousFeeRateSettings: FeeRateSettingsSnapshot? = null
    private var previousTxSettings: TxSettingsSnapshot? = null
    private var previousTimeUnit: String? = null
    
    // Data classes for tracking relevant setting changes
    private data class BlockSettingsSnapshot(
        val newBlockEnabled: Boolean,
        val newBlockFrequency: Int,
        val specificBlockEnabled: Boolean,
        val specificBlockFrequency: Int,
        val targetBlockHeight: Int?
    )
    
    private data class MempoolSettingsSnapshot(
        val enabled: Boolean,
        val frequency: Int,
        val threshold: Float
    )
    
    private data class FeeRateSettingsSnapshot(
        val enabled: Boolean,
        val frequency: Int,
        val threshold: Double,
        val feeRateType: FeeRateType,
        val aboveThreshold: Boolean
    )
    
    private data class TxSettingsSnapshot(
        val enabled: Boolean,
        val frequency: Int,
        val transactionId: String
    )
    
    // Helper function to get API safely, initializing if needed
    private fun getApi(): MempoolApi? {
        if (api != null) return api
        if (NetworkClient.isInitialized.value) {
            try {
                api = NetworkClient.mempoolApi
                return api
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to get API: ${e.message}", e)
            }
        }
        return null
    }
    
    // Helper function to get fee rates with fallback support
    private suspend fun getFeeRatesWithFallback(usePreciseFees: Boolean): Response<FeeRates> {
        val currentApi = getApi() ?: return Response.error(500, "API not initialized".toResponseBody(null))
        
        return FeeRatesHelper.getFeeRatesWithFallback(
            api = currentApi,
            usePreciseFees = usePreciseFees,
            settingsRepository = settingsRepository
        ) ?: currentApi.getFeeRates()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunningFlag = true
        settingsRepository = SettingsRepository.getInstance(applicationContext)
        NetworkClient.initialize(applicationContext)
        createNotificationChannel()

        // Start Tor foreground service if Tor is connected
        val torManager = TorManager.getInstance()
        if (torManager.torStatus.value == TorStatus.CONNECTED) {
            torManager.startForegroundService(applicationContext)
        }

        // Update settings to show service is enabled when starting
        serviceScope.launch {
            settingsRepository.updateSettings(
                settingsRepository.settings.value.copy(isServiceEnabled = true)
            )
        }

        startForegroundSafely()
        
        // Initialize API and start monitoring asynchronously
        serviceScope.launch {
            // Wait for NetworkClient to be initialized before accessing API
            var attempts = 0
            while (!NetworkClient.isInitialized.value && attempts < 50) {
                delay(100)
                attempts++
            }
            if (NetworkClient.isInitialized.value) {
                try {
                    api = NetworkClient.mempoolApi
                    startMonitoring()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to initialize API: ${e.message}", e)
                    // Try to start monitoring anyway - API calls will fail gracefully
                    startMonitoring()
                }
            } else {
                android.util.Log.e(TAG, "NetworkClient initialization timeout")
                // Try to access anyway - it might throw but we'll handle it
                try {
                    api = NetworkClient.mempoolApi
                    startMonitoring()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to get API: ${e.message}", e)
                    // Start monitoring anyway - monitoring functions will handle API errors
                    startMonitoring()
                }
            }
        }

        // Monitor Tor status changes to manage foreground service
        serviceScope.launch {
            torManager.torStatus.collect { status ->
                when (status) {
                    TorStatus.CONNECTED -> torManager.startForegroundService(applicationContext)
                    TorStatus.DISCONNECTED, TorStatus.ERROR -> torManager.stopForegroundService(applicationContext)
                    TorStatus.CONNECTING -> {} // Do nothing for CONNECTING state
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                // Update settings to show service is disabled
                serviceScope.launch {
                    settingsRepository.updateSettings(
                        settingsRepository.settings.value.copy(isServiceEnabled = false)
                    )
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Return START_STICKY to ensure service restarts if killed
        // Always try to start foreground - this ensures notification is shown even if
        // service was started before permission was granted
        startForegroundSafely()
        return START_STICKY
    }
    
    private fun startForegroundSafely() {
        try {
            // Ensure notification channel is created before starting foreground
            createNotificationChannel()
            val notification = createForegroundNotification()
            
            // Always try to start foreground, even if already started
            // This ensures notification is shown if service was started before permission was granted
            startForeground(NOTIFICATION_ID, notification)
            isForegroundStarted = true
            android.util.Log.d(TAG, "Foreground service started successfully with notification")
        } catch (e: Exception) {
            // Handle ForegroundServiceStartNotAllowedException and other exceptions
            android.util.Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
            // If we can't start as foreground, try to continue as regular service
            // This might happen if Android has exhausted the time limit for foreground services
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                android.util.Log.w(TAG, "Foreground service start not allowed, continuing as regular service")
                // The service will still run, but without foreground priority
                // This is acceptable for background monitoring tasks
            } else {
                // For other exceptions, try to show the notification anyway using NotificationManager
                try {
                    val notificationManager = getSystemService(NotificationManager::class.java)
                    val notification = createForegroundNotification()
                    notificationManager.notify(NOTIFICATION_ID, notification)
                    android.util.Log.d(TAG, "Notification shown via NotificationManager as fallback")
                } catch (e2: Exception) {
                    android.util.Log.e(TAG, "Failed to show notification via NotificationManager: ${e2.message}", e2)
                }
            }
        }
    }

    // Data class to group all monitoring-relevant settings for distinctUntilChanged filtering
    private data class MonitoringSettings(
        val newBlockEnabled: Boolean,
        val newBlockFrequency: Int,
        val specificBlockEnabled: Boolean,
        val specificBlockFrequency: Int,
        val targetBlockHeight: Int?,
        val mempoolEnabled: Boolean,
        val mempoolFrequency: Int,
        val mempoolThreshold: Float,
        val mempoolAboveThreshold: Boolean,
        val feeEnabled: Boolean,
        val feeFrequency: Int,
        val feeThreshold: Double,
        val feeType: FeeRateType,
        val feeAboveThreshold: Boolean,
        val txEnabled: Boolean,
        val txFrequency: Int,
        val txId: String
    )
    
    private fun startMonitoring() {
        serviceScope.launch {
            // Monitor only monitoring-relevant setting changes using distinctUntilChangedBy
            // This prevents unnecessary re-collection for unrelated setting changes (e.g. usePreciseFees)
            settingsRepository.settings
                .distinctUntilChangedBy { settings ->
                    MonitoringSettings(
                        newBlockEnabled = settings.newBlockNotificationEnabled,
                        newBlockFrequency = settings.newBlockCheckFrequency,
                        specificBlockEnabled = settings.specificBlockNotificationEnabled,
                        specificBlockFrequency = settings.specificBlockCheckFrequency,
                        targetBlockHeight = settings.targetBlockHeight,
                        mempoolEnabled = settings.mempoolSizeNotificationsEnabled,
                        mempoolFrequency = settings.mempoolCheckFrequency,
                        mempoolThreshold = settings.mempoolSizeThreshold,
                        mempoolAboveThreshold = settings.mempoolSizeAboveThreshold,
                        feeEnabled = settings.feeRatesNotificationsEnabled,
                        feeFrequency = settings.feeRatesCheckFrequency,
                        feeThreshold = settings.feeRateThreshold,
                        feeType = settings.selectedFeeRateType,
                        feeAboveThreshold = settings.feeRateAboveThreshold,
                        txEnabled = settings.txConfirmationEnabled,
                        txFrequency = settings.txConfirmationFrequency,
                        txId = settings.transactionId
                    )
                }
                .collect { settings ->
                val timeUnit = settingsRepository.getNotificationTimeUnit()
                val delayMultiplier = if (timeUnit == "seconds") 1000L else 60000L
                
                // Create snapshots of current settings for each job type
                val currentBlockSettings = BlockSettingsSnapshot(
                    newBlockEnabled = settings.newBlockNotificationEnabled,
                    newBlockFrequency = settings.newBlockCheckFrequency,
                    specificBlockEnabled = settings.specificBlockNotificationEnabled,
                    specificBlockFrequency = settings.specificBlockCheckFrequency,
                    targetBlockHeight = settings.targetBlockHeight
                )
                
                val currentMempoolSettings = MempoolSettingsSnapshot(
                    enabled = settings.mempoolSizeNotificationsEnabled,
                    frequency = settings.mempoolCheckFrequency,
                    threshold = settings.mempoolSizeThreshold
                )
                
                val currentFeeRateSettings = FeeRateSettingsSnapshot(
                    enabled = settings.feeRatesNotificationsEnabled,
                    frequency = settings.feeRatesCheckFrequency,
                    threshold = settings.feeRateThreshold,
                    feeRateType = settings.selectedFeeRateType,
                    aboveThreshold = settings.feeRateAboveThreshold
                )
                
                val currentTxSettings = TxSettingsSnapshot(
                    enabled = settings.txConfirmationEnabled,
                    frequency = settings.txConfirmationFrequency,
                    transactionId = settings.transactionId
                )
                
                // Check if time unit changed - this requires restarting all jobs
                val timeUnitChanged = previousTimeUnit != null && previousTimeUnit != timeUnit
                if (timeUnitChanged) {
                    android.util.Log.d(TAG, "Time unit changed from $previousTimeUnit to $timeUnit, restarting all jobs")
                    monitoringJobs.values.forEach { it.cancel() }
                    monitoringJobs.clear()
                    lastCheckTimes.clear()
                }
                previousTimeUnit = timeUnit

                // Block Notifications - only restart if block settings changed
                val blockSettingsChanged = previousBlockSettings != currentBlockSettings
                if (blockSettingsChanged || timeUnitChanged) {
                    if (blockSettingsChanged) {
                        android.util.Log.d(TAG, "Block settings changed, restarting blocks job")
                    }
                    monitoringJobs["blocks"]?.cancel()
                    monitoringJobs.remove("blocks")
                    lastCheckTimes.remove("blocks")
                    
                    val newBlockEnabled = settings.newBlockNotificationEnabled && settings.newBlockCheckFrequency > 0
                    val specificBlockEnabled = settings.specificBlockNotificationEnabled && 
                        settings.specificBlockCheckFrequency > 0 && 
                        settings.targetBlockHeight != null
                    
                    if (newBlockEnabled || specificBlockEnabled) {
                        val blockCheckFrequency = when {
                            newBlockEnabled && specificBlockEnabled -> 
                                minOf(settings.newBlockCheckFrequency, settings.specificBlockCheckFrequency)
                            newBlockEnabled -> settings.newBlockCheckFrequency
                            else -> settings.specificBlockCheckFrequency
                        }
                        
                        android.util.Log.d(TAG, "Starting blocks monitoring job. newBlockEnabled=$newBlockEnabled, specificBlockEnabled=$specificBlockEnabled, frequency=$blockCheckFrequency $timeUnit")
                        
                        monitoringJobs["blocks"] = launch {
                            delay(blockCheckFrequency * delayMultiplier)
                            while (isActive) {
                                val now = System.currentTimeMillis()
                                val lastCheck = lastCheckTimes["blocks"] ?: 0L
                                val interval = blockCheckFrequency * delayMultiplier

                                if (now - lastCheck >= interval) {
                                    android.util.Log.d(TAG, "Checking blocks. Interval: $blockCheckFrequency $timeUnit")
                                    checkNewBlocks()
                                    lastCheckTimes["blocks"] = now
                                }
                                delay(1000)
                            }
                        }
                    }
                }
                previousBlockSettings = currentBlockSettings

                // Mempool Size Notifications - only restart if mempool settings changed
                val mempoolSettingsChanged = previousMempoolSettings != currentMempoolSettings
                if (mempoolSettingsChanged || timeUnitChanged) {
                    if (mempoolSettingsChanged) {
                        android.util.Log.d(TAG, "Mempool settings changed, restarting mempool job")
                    }
                    monitoringJobs["mempoolSize"]?.cancel()
                    monitoringJobs.remove("mempoolSize")
                    lastCheckTimes.remove("mempoolSize")
                    
                    if (settings.mempoolSizeNotificationsEnabled && 
                        settings.mempoolCheckFrequency > 0 && 
                        settings.mempoolSizeThreshold > 0) {
                        monitoringJobs["mempoolSize"] = launch {
                            delay(settings.mempoolCheckFrequency * delayMultiplier)
                            while (isActive) {
                                val now = System.currentTimeMillis()
                                val lastCheck = lastCheckTimes["mempoolSize"] ?: 0L
                                val interval = settings.mempoolCheckFrequency * delayMultiplier

                                if (now - lastCheck >= interval) {
                                    android.util.Log.d(TAG, "Checking mempool size. Interval: ${settings.mempoolCheckFrequency} $timeUnit")
                                    checkMempoolSize(settings.mempoolSizeThreshold)
                                    lastCheckTimes["mempoolSize"] = now
                                }
                                delay(1000)
                            }
                        }
                    }
                }
                previousMempoolSettings = currentMempoolSettings

                // Fee Rate Notifications - only restart if fee rate settings changed
                val feeRateSettingsChanged = previousFeeRateSettings != currentFeeRateSettings
                if (feeRateSettingsChanged || timeUnitChanged) {
                    if (feeRateSettingsChanged) {
                        android.util.Log.d(TAG, "Fee rate settings changed, restarting fee rates job")
                    }
                    monitoringJobs["feeRates"]?.cancel()
                    monitoringJobs.remove("feeRates")
                    lastCheckTimes.remove("feeRates")
                    
                    if (settings.feeRatesNotificationsEnabled && 
                        settings.feeRatesCheckFrequency > 0 && 
                        settings.feeRateThreshold > 0) {
                        monitoringJobs["feeRates"] = launch {
                            delay(settings.feeRatesCheckFrequency * delayMultiplier)
                            while (isActive) {
                                val now = System.currentTimeMillis()
                                val lastCheck = lastCheckTimes["feeRates"] ?: 0L
                                val interval = settings.feeRatesCheckFrequency * delayMultiplier

                                if (now - lastCheck >= interval) {
                                    android.util.Log.d(TAG, "Checking fee rates. Interval: ${settings.feeRatesCheckFrequency} $timeUnit")
                                    checkFeeRates(settings.selectedFeeRateType, settings.feeRateThreshold)
                                    lastCheckTimes["feeRates"] = now
                                }
                                delay(1000)
                            }
                        }
                    }
                }
                previousFeeRateSettings = currentFeeRateSettings

                // Transaction Confirmation Notifications - only restart if tx settings changed
                val txSettingsChanged = previousTxSettings != currentTxSettings
                if (txSettingsChanged || timeUnitChanged) {
                    if (txSettingsChanged) {
                        android.util.Log.d(TAG, "Transaction settings changed, restarting tx confirmation job")
                    }
                    monitoringJobs["txConfirmation"]?.cancel()
                    monitoringJobs.remove("txConfirmation")
                    lastCheckTimes.remove("txConfirmation")
                    
                    if (settings.txConfirmationEnabled && 
                        settings.txConfirmationFrequency > 0 && 
                        settings.transactionId.isNotEmpty() && 
                        settings.transactionId.length >= 64) {
                        monitoringJobs["txConfirmation"] = launch {
                            delay(settings.txConfirmationFrequency * delayMultiplier)
                            while (isActive) {
                                val now = System.currentTimeMillis()
                                val lastCheck = lastCheckTimes["txConfirmation"] ?: 0L
                                val interval = settings.txConfirmationFrequency * delayMultiplier

                                if (now - lastCheck >= interval) {
                                    android.util.Log.d(TAG, "Checking transaction confirmation. Interval: ${settings.txConfirmationFrequency} $timeUnit")
                                    checkTransactionConfirmation(settings.transactionId)
                                    lastCheckTimes["txConfirmation"] = now
                                }
                            delay(1000)
                        }
                    }
                }
                }
                previousTxSettings = currentTxSettings
            }
        }
    }

    @SuppressLint("DefaultLocale")
    private suspend fun checkMempoolSize(threshold: Float) {
        try {
            android.util.Log.d(TAG, "Making mempool size API call...")
            val settings = settingsRepository.settings.first()

            // Remove this early return
            // if (settings.hasNotifiedForMempoolSize) {
            //     return
            // }

            val currentApi = getApi() ?: return
            val mempoolInfo = currentApi.getMempoolInfo()
            if (mempoolInfo.isSuccessful) {
                val currentSize = mempoolInfo.body()?.vsize?.toFloat()?.div(1_000_000f)
                android.util.Log.d(TAG, "Current mempool size: $currentSize vMB, Threshold: $threshold")

                if (currentSize != null) {
                    val shouldNotify = if (settings.mempoolSizeAboveThreshold) {
                        currentSize > threshold
                    } else {
                        currentSize < threshold
                    }

                    if (shouldNotify) {
                        val condition = if (settings.mempoolSizeAboveThreshold) "risen above" else "fallen below"
                        // Generate unique notification ID based on settings
                        // This allows new notifications when settings change while preventing spam for same settings
                        val notificationId = NOTIFICATION_ID_MEMPOOL_SIZE + 
                            (threshold.hashCode() xor settings.mempoolSizeAboveThreshold.hashCode())
                        showNotificationWithId(
                            "Mempool Size Alert",
                            "Mempool size has $condition $threshold vMB and is now ${String.format("%.2f", currentSize)} vMB",
                            notificationId,
                            checkDismissal = true
                        )
                    }
                }
            } else {
                android.util.Log.e(TAG, "Mempool size API call failed: ${mempoolInfo.errorBody()?.string()}")
            }
        } catch (_: CancellationException) {
            // Job was cancelled due to settings change - this is expected, just log at debug level
            android.util.Log.d(TAG, "Mempool size check cancelled (settings changed)")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error checking mempool size: ${e.message}", e)
        }
    }

    private suspend fun checkFeeRates(feeRateType: FeeRateType, threshold: Double) {
        try {
            android.util.Log.d(TAG, "Making fee rates API call...")
            val settings = settingsRepository.settings.first()

            // Remove this early return as it prevents checking new rates
            // if (settings.hasNotifiedForFeeRate) {
            //     return
            // }

            val feeRates = getFeeRatesWithFallback(settings.usePreciseFees)
            if (feeRates.isSuccessful) {
                val rates = feeRates.body()
                android.util.Log.d(TAG, "Fee rates API response: ${rates?.toString()}")

                if (rates != null) {
                    val currentRate = when (feeRateType) {
                        FeeRateType.NEXT_BLOCK -> rates.fastestFee
                        FeeRateType.THREE_BLOCKS -> rates.halfHourFee
                        FeeRateType.SIX_BLOCKS -> rates.hourFee
                        FeeRateType.DAY_BLOCKS -> rates.economyFee
                    }
                    android.util.Log.d(TAG, "Current rate for $feeRateType: $currentRate, Threshold: $threshold")

                    val shouldNotify = if (settings.feeRateAboveThreshold) {
                        currentRate > threshold
                    } else {
                        currentRate < threshold
                    }

                    if (shouldNotify) {
                        val feeRateTypeString = when (feeRateType) {
                            FeeRateType.NEXT_BLOCK -> "Next Block"
                            FeeRateType.THREE_BLOCKS -> "3 Block"
                            FeeRateType.SIX_BLOCKS -> "6 Block"
                            FeeRateType.DAY_BLOCKS -> "1 Day"
                        }
                        val condition = if (settings.feeRateAboveThreshold) "risen above" else "fallen below"
                        
                        // Format fee rates to show max 2 decimal places
                        val formattedThreshold = if (threshold % 1.0 == 0.0) {
                            threshold.toInt().toString()
                        } else {
                            String.format(java.util.Locale.US, "%.2f", threshold)
                        }
                        val formattedCurrentRate = if (currentRate % 1.0 == 0.0) {
                            currentRate.toInt().toString()
                        } else {
                            String.format(java.util.Locale.US, "%.2f", currentRate)
                        }
                        
                        // Generate unique notification ID based on settings
                        // This allows new notifications when settings change while preventing spam for same settings
                        val notificationId = NOTIFICATION_ID_FEE_RATE + 
                            (threshold.hashCode() xor feeRateType.hashCode() xor settings.feeRateAboveThreshold.hashCode())
                        showNotificationWithId(
                            "Fee Rate Alert",
                            "$feeRateTypeString fee rate has $condition $formattedThreshold sat/vB and is currently at $formattedCurrentRate sat/vB",
                            notificationId,
                            checkDismissal = true
                        )
                    }
                }
            } else {
                android.util.Log.e(TAG, "Fee rates API call failed: ${feeRates.errorBody()?.string()}")
            }
        } catch (_: CancellationException) {
            // Job was cancelled due to settings change - this is expected, just log at debug level
            android.util.Log.d(TAG, "Fee rates check cancelled (settings changed)")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error checking fee rates: ${e.message}", e)
        }
    }

    private suspend fun checkNewBlocks() {
        try {
            android.util.Log.d(TAG, "Making block height API call...")
            val settings = settingsRepository.settings.first()

            // Skip if neither notification is enabled
            if (!settings.newBlockNotificationEnabled && !settings.specificBlockNotificationEnabled) {
                return
            }

            val currentApi = getApi() ?: return
            val blockHeight = currentApi.getBlockHeight()
            if (blockHeight.isSuccessful) {
                val currentHeight = blockHeight.body()
                android.util.Log.d(TAG, "Current block height: $currentHeight, Last height: $lastBlockHeight")

                if (currentHeight != null) {
                    // Check for new block notification
                    if (settings.newBlockNotificationEnabled) {
                        if (lastBlockHeight != null && currentHeight > lastBlockHeight!!) {
                            android.util.Log.d(TAG, "New block detected! Sending notification for block $currentHeight")
                            val formattedHeight = String.format(java.util.Locale.US, "%,d", currentHeight) + if (currentHeight >= 1_000_000) " for" else ""
                            showNewBlockNotification("Block $formattedHeight has been mined")
                        } else if (lastBlockHeight == null) {
                            android.util.Log.d(TAG, "First block check, establishing baseline at block $currentHeight")
                        } else {
                            android.util.Log.d(TAG, "No new block yet. Current: $currentHeight, Last: $lastBlockHeight")
                        }
                    }

                    // Check for specific block height notification
                    if (settings.specificBlockNotificationEnabled &&
                        !settings.hasNotifiedForTargetBlock &&
                        settings.targetBlockHeight != null &&
                        currentHeight >= settings.targetBlockHeight
                    ) {
                        // Generate unique notification ID based on target block height
                        // This allows new notifications when target changes while preventing spam for same target
                        val notificationId = NOTIFICATION_ID_SPECIFIC_BLOCK + settings.targetBlockHeight
                        
                        // Only notify if user has dismissed previous notification for this target
                        if (!isNotificationActive(notificationId)) {
                            val formattedHeight = String.format(java.util.Locale.US, "%,d", settings.targetBlockHeight) + if (settings.targetBlockHeight >= 1_000_000) " for" else ""
                            showNotificationWithId(
                                "Target Block Height Reached",
                                "Block height $formattedHeight has been reached!",
                                notificationId
                            )
                            settingsRepository.updateSettings(
                                settings.copy(hasNotifiedForTargetBlock = true)
                            )
                        }
                    }

                    lastBlockHeight = currentHeight
                }
            } else {
                android.util.Log.e(TAG, "Block height API call failed: ${blockHeight.errorBody()?.string()}")
            }
        } catch (_: CancellationException) {
            // Job was cancelled due to settings change - this is expected, just log at debug level
            android.util.Log.d(TAG, "Block height check cancelled (settings changed)")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error checking block height: ${e.message}", e)
        }
    }

    private suspend fun checkTransactionConfirmation(txid: String) {
        try {
            val settings = settingsRepository.settings.first()
            if (settings.hasNotifiedForCurrentTx) {
                return
            }

            val currentApi = getApi() ?: return
            val response = currentApi.getTransaction(txid)
            if (response.isSuccessful) {
                val transaction = response.body()
                if (transaction?.status?.confirmed == true) {
                    // Generate unique notification ID based on txid
                    // This allows new notifications when txid changes while preventing spam for same txid
                    val notificationId = NOTIFICATION_ID_TX_CONFIRMATION + txid.hashCode()
                    
                    // Only notify if user has dismissed previous notification for this txid
                    if (!isNotificationActive(notificationId)) {
                        // Format txid: first 4 and last 4 characters
                        val txidPreview = if (txid.length >= 8) {
                            "${txid.take(4)}...${txid.takeLast(4)}"
                        } else {
                            txid // Fallback for short txids (shouldn't happen for valid Bitcoin txids)
                        }
                        showNotificationWithId(
                            "Transaction Confirmed",
                            "Your transaction has been confirmed in block ${transaction.status.block_height} (TxID: $txidPreview)",
                            notificationId
                        )
                        settingsRepository.updateSettings(
                            settings.copy(hasNotifiedForCurrentTx = true)
                        )
                    }
                }
            }
        } catch (_: CancellationException) {
            // Job was cancelled due to settings change - this is expected, just log at debug level
            android.util.Log.d(TAG, "Transaction confirmation check cancelled (settings changed)")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error checking transaction confirmation: ${e.message}", e)
        }
    }

    private fun createForegroundNotification(): Notification {
        // Create pending intent to launch the app
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        // Create stop service action
        val stopIntent = Intent(this, NotificationService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mempal")
            .setContentText("Monitoring Bitcoin Network")
            .setSmallIcon(R.drawable.ic_cube)
            .setContentIntent(pendingIntent)
            .setOngoing(true)  // Make notification persistent
            .addAction(R.drawable.ic_cube, "Stop Service", stopPendingIntent)  // Add stop action
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Mempal Notifications"
            val descriptionText = "Bitcoin network monitoring notifications"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            android.util.Log.d(TAG, "Notification channel created: $CHANNEL_ID")
        }
    }

    /**
     * Check if a notification with the given ID is still active (not dismissed by user)
     */
    private fun isNotificationActive(notificationId: Int): Boolean {
        val notificationManager = getSystemService(NotificationManager::class.java)
        return notificationManager.activeNotifications.any { it.id == notificationId }
    }
    
    /**
     * Show a new block notification with a dynamic ID (always shows)
     */
    private fun showNewBlockNotification(content: String) {
        showNotificationWithId("New Block Mined", content, System.currentTimeMillis().toInt())
    }
    
    /**
     * Show a notification with a specific ID.
     * If checkDismissal is true, won't show if a notification with this ID is still active.
     */
    private fun showNotificationWithId(
        title: String, 
        content: String, 
        notificationId: Int,
        checkDismissal: Boolean = false
    ) {
        // If checking dismissal and notification is still active, don't show a new one
        if (checkDismissal && isNotificationActive(notificationId)) {
            android.util.Log.d(TAG, "Notification $notificationId still active, skipping new notification")
            return
        }
        
        // Create pending intent to launch the app
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_cube)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)  // Automatically remove the notification when tapped
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager =
            getSystemService(NotificationManager::class.java)

        android.util.Log.d(TAG, "Dispatching notification: $title (ID: $notificationId)")
        notificationManager.notify(notificationId, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunningFlag = false
        isForegroundStarted = false
        monitoringJobs.values.forEach { it.cancel() }
        monitoringJobs.clear()
        serviceScope.cancel()
        // Don't call NetworkClient.cleanup() here - it's shared with the main app
        // The main app manages NetworkClient lifecycle, not the service

        // Stop Tor foreground service
        TorManager.getInstance().stopForegroundService(applicationContext)

        // Only update settings if service is actually being destroyed (not restarted)
        if (!isServiceRestarting()) {
            settingsRepository.settings.value.let { currentSettings ->
                settingsRepository.updateSettings(currentSettings.copy(isServiceEnabled = false))
            }
        }
        lastBlockHeight = null
    }

    private fun isServiceRestarting(): Boolean {
        // Check if service is being restarted by system
        return try {
            val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            manager.runningAppProcesses?.any {
                it.processName == packageName && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
            } == true
        } catch (_: Exception) {
            false
        }
    }
}