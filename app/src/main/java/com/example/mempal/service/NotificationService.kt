package com.example.mempal.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.mempal.R
import com.example.mempal.api.NetworkClient
import com.example.mempal.model.FeeRateType
import com.example.mempal.repository.SettingsRepository
import com.example.mempal.tor.TorManager
import com.example.mempal.tor.TorStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class NotificationService : Service() {
    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "mempal_notifications"
        private const val TAG = "NotificationService"
        const val ACTION_STOP_SERVICE = "com.example.mempal.STOP_SERVICE"

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
    private val api = NetworkClient.mempoolApi
    private var lastBlockHeight: Int? = null
    private val monitoringJobs = mutableMapOf<String, Job>()
    private var lastCheckTimes = mutableMapOf<String, Long>()

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

        startForeground(NOTIFICATION_ID, createForegroundNotification())
        startMonitoring()

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
        startForeground(NOTIFICATION_ID, createForegroundNotification())
        return START_STICKY
    }

    private fun startMonitoring() {
        serviceScope.launch {
            // Monitor both settings and time unit changes
            settingsRepository.settings.collect { settings ->
                android.util.Log.d(TAG, "Settings updated, restarting monitoring jobs")
                monitoringJobs.values.forEach { it.cancel() }
                monitoringJobs.clear()
                lastCheckTimes.clear()

                val timeUnit = settingsRepository.getNotificationTimeUnit()
                val delayMultiplier = if (timeUnit == "seconds") 1000L else 60000L
                android.util.Log.d(TAG, "Time unit: $timeUnit, Delay multiplier: $delayMultiplier")

                // New Block Notifications - requires frequency > 0
                if (settings.newBlockNotificationEnabled && settings.newBlockCheckFrequency > 0) {
                    monitoringJobs["newBlock"] = launch {
                        delay(settings.newBlockCheckFrequency * delayMultiplier)
                        while (isActive) {
                            val now = System.currentTimeMillis()
                            val lastCheck = lastCheckTimes["newBlock"] ?: 0L
                            val interval = settings.newBlockCheckFrequency * delayMultiplier

                            if (now - lastCheck >= interval) {
                                android.util.Log.d(TAG, "Checking new blocks. Interval: ${settings.newBlockCheckFrequency} $timeUnit")
                                checkNewBlocks()
                                lastCheckTimes["newBlock"] = now
                            }
                            delay(1000)
                        }
                    }
                }

                // Specific Block Notifications - requires target height and frequency > 0
                if (settings.specificBlockNotificationEnabled && 
                    settings.specificBlockCheckFrequency > 0 && 
                    settings.targetBlockHeight != null) {
                    monitoringJobs["specificBlock"] = launch {
                        delay(settings.specificBlockCheckFrequency * delayMultiplier)
                        while (isActive) {
                            val now = System.currentTimeMillis()
                            val lastCheck = lastCheckTimes["specificBlock"] ?: 0L
                            val interval = settings.specificBlockCheckFrequency * delayMultiplier

                            if (now - lastCheck >= interval) {
                                android.util.Log.d(TAG, "Checking specific block. Interval: ${settings.specificBlockCheckFrequency} $timeUnit")
                                checkNewBlocks()
                                lastCheckTimes["specificBlock"] = now
                            }
                            delay(1000)
                        }
                    }
                }

                // Mempool Size Notifications - requires threshold > 0 and frequency > 0
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

                // Fee Rate Notifications - requires threshold > 0 and frequency > 0
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

                // Transaction Confirmation Notifications - requires valid txid and frequency > 0
                if (settings.txConfirmationEnabled && 
                    settings.txConfirmationFrequency > 0 && 
                    settings.transactionId.isNotEmpty() && 
                    settings.transactionId.length >= 64) {  // Valid txid is 64 chars
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

            val mempoolInfo = api.getMempoolInfo()
            if (mempoolInfo.isSuccessful) {
                val currentSize = mempoolInfo.body()?.vsize?.toFloat()?.div(1_000_000f)
                android.util.Log.d(TAG, "Current mempool size: $currentSize vMB, Threshold: $threshold")

                if (currentSize != null) {
                    val shouldNotify = if (settings.mempoolSizeAboveThreshold) {
                        currentSize > threshold
                    } else {
                        currentSize < threshold
                    }

                    if (shouldNotify && !settings.hasNotifiedForMempoolSize) {
                        val condition = if (settings.mempoolSizeAboveThreshold) "risen above" else "fallen below"
                        showNotification(
                            "Mempool Size Alert",
                            "Mempool size has $condition $threshold vMB and is now ${String.format("%.2f", currentSize)} vMB"
                        )
                        settingsRepository.updateSettings(
                            settings.copy(hasNotifiedForMempoolSize = true)
                        )
                    }
                }
            } else {
                android.util.Log.e(TAG, "Mempool size API call failed: ${mempoolInfo.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error checking mempool size: ${e.message}", e)
        }
    }

    private suspend fun checkFeeRates(feeRateType: FeeRateType, threshold: Int) {
        try {
            android.util.Log.d(TAG, "Making fee rates API call...")
            val settings = settingsRepository.settings.first()

            // Remove this early return as it prevents checking new rates
            // if (settings.hasNotifiedForFeeRate) {
            //     return
            // }

            val feeRates = api.getFeeRates()
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

                    if (shouldNotify && !settings.hasNotifiedForFeeRate) {
                        val feeRateTypeString = when (feeRateType) {
                            FeeRateType.NEXT_BLOCK -> "Next Block"
                            FeeRateType.THREE_BLOCKS -> "3 Block"
                            FeeRateType.SIX_BLOCKS -> "6 Block"
                            FeeRateType.DAY_BLOCKS -> "1 Day"
                        }
                        val condition = if (settings.feeRateAboveThreshold) "risen above" else "fallen below"
                        showNotification(
                            "Fee Rate Alert",
                            "$feeRateTypeString fee rate has $condition $threshold sat/vB and is currently at $currentRate sat/vB"
                        )
                        settingsRepository.updateSettings(
                            settings.copy(hasNotifiedForFeeRate = true)
                        )
                    }
                }
            } else {
                android.util.Log.e(TAG, "Fee rates API call failed: ${feeRates.errorBody()?.string()}")
            }
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

            val blockHeight = api.getBlockHeight()
            if (blockHeight.isSuccessful) {
                val currentHeight = blockHeight.body()
                android.util.Log.d(TAG, "Current block height: $currentHeight, Last height: $lastBlockHeight")

                if (currentHeight != null) {
                    // Check for new block notification
                    if (settings.newBlockNotificationEnabled &&
                        lastBlockHeight != null &&
                        currentHeight > lastBlockHeight!!
                    ) {
                        showNotification(
                            "New Block Mined",
                            "Block #$currentHeight has been mined"
                        )
                    }

                    // Check for specific block height notification
                    if (settings.specificBlockNotificationEnabled &&
                        !settings.hasNotifiedForTargetBlock &&
                        settings.targetBlockHeight != null &&
                        currentHeight >= settings.targetBlockHeight
                    ) {
                        showNotification(
                            "Target Block Height Reached",
                            "Block height ${settings.targetBlockHeight} has been reached!"
                        )
                        settingsRepository.updateSettings(
                            settings.copy(hasNotifiedForTargetBlock = true)
                        )
                    }

                    lastBlockHeight = currentHeight
                }
            } else {
                android.util.Log.e(TAG, "Block height API call failed: ${blockHeight.errorBody()?.string()}")
            }
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

            val response = api.getTransaction(txid)
            if (response.isSuccessful) {
                val transaction = response.body()
                if (transaction?.status?.confirmed == true) {
                    showNotification(
                        "Transaction Confirmed",
                        "Your transaction has been confirmed in block ${transaction.status.block_height}"
                    )
                    settingsRepository.updateSettings(
                        settings.copy(hasNotifiedForCurrentTx = true)
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
        }
    }

    private fun showNotification(title: String, content: String) {
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

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunningFlag = false
        monitoringJobs.values.forEach { it.cancel() }
        monitoringJobs.clear()
        serviceScope.cancel()
        NetworkClient.cleanup()

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