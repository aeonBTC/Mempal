package com.example.mempal.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.mempal.R
import com.example.mempal.api.NetworkClient
import com.example.mempal.model.FeeRateType
import com.example.mempal.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class NotificationService : Service() {
    companion object {
        const val NOTIFICATION_ID = 1
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsRepository: SettingsRepository
    private val api = NetworkClient.mempoolApi
    private val channelId = "mempal_notifications"
    private var lastBlockHeight: Int? = null
    private val monitoringJobs = mutableMapOf<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository.getInstance(applicationContext)
        NetworkClient.initialize(applicationContext)
        createNotificationChannel()

        // Update settings to show service is enabled when starting
        serviceScope.launch {
            settingsRepository.updateSettings(
                settingsRepository.settings.value.copy(isServiceEnabled = true)
            )
        }

        startForeground(NOTIFICATION_ID, createForegroundNotification())
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Return START_STICKY to ensure service restarts if killed
        return START_STICKY
    }

    private fun startMonitoring() {
        serviceScope.launch {
            settingsRepository.settings.collect { settings ->
                monitoringJobs.values.forEach { it.cancel() }
                monitoringJobs.clear()

                if (settings.newBlockNotificationEnabled) {
                    monitoringJobs["newBlock"] = launch {
                        while (isActive) {
                            checkNewBlocks()
                            delay(settings.newBlockCheckFrequency * 60 * 1000L)
                        }
                    }
                }

                if (settings.specificBlockNotificationEnabled) {
                    monitoringJobs["specificBlock"] = launch {
                        while (isActive) {
                            checkNewBlocks()
                            delay(settings.specificBlockCheckFrequency * 60 * 1000L)
                        }
                    }
                }

                if (settings.mempoolSizeNotificationsEnabled) {
                    monitoringJobs["mempoolSize"] = launch {
                        while (isActive) {
                            checkMempoolSize(settings.mempoolSizeThreshold)
                            delay(settings.mempoolCheckFrequency * 60 * 1000L)
                        }
                    }
                }

                if (settings.feeRatesNotificationsEnabled) {
                    monitoringJobs["feeRates"] = launch {
                        while (isActive) {
                            checkFeeRates(settings.selectedFeeRateType, settings.feeRateThreshold)
                            delay(settings.feeRatesCheckFrequency * 60 * 1000L)
                        }
                    }
                }

                if (settings.txConfirmationEnabled && settings.transactionId.isNotEmpty()) {
                    monitoringJobs["txConfirmation"] = launch {
                        while (isActive) {
                            checkTransactionConfirmation(settings.transactionId)
                            delay(settings.txConfirmationFrequency * 60 * 1000L)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("DefaultLocale")
    private suspend fun checkMempoolSize(threshold: Float) {
        try {
            val settings = settingsRepository.settings.first()
            if (settings.hasNotifiedForMempoolSize) {
                return
            }

            val mempoolInfo = api.getMempoolInfo()
            if (mempoolInfo.isSuccessful) {
                val currentSize = mempoolInfo.body()?.vsize?.toFloat()?.div(1_000_000f) ?: return
                val shouldNotify = if (settings.mempoolSizeAboveThreshold) {
                    currentSize > threshold
                } else {
                    currentSize < threshold
                }

                if (shouldNotify) {
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun checkFeeRates(feeRateType: FeeRateType, threshold: Int) {
        try {
            val settings = settingsRepository.settings.first()
            if (settings.hasNotifiedForFeeRate) {
                return
            }

            val feeRates = api.getFeeRates()
            if (feeRates.isSuccessful) {
                val rates = feeRates.body() ?: return
                val currentRate = when (feeRateType) {
                    FeeRateType.NEXT_BLOCK -> rates.fastestFee
                    FeeRateType.TWO_BLOCKS -> rates.halfHourFee
                    FeeRateType.FOUR_BLOCKS -> rates.hourFee
                    FeeRateType.DAY_BLOCKS -> rates.economyFee
                }

                val shouldNotify = if (settings.feeRateAboveThreshold) {
                    currentRate > threshold
                } else {
                    currentRate < threshold
                }

                if (shouldNotify) {
                    val feeRateTypeString = when (feeRateType) {
                        FeeRateType.NEXT_BLOCK -> "Next Block"
                        FeeRateType.TWO_BLOCKS -> "3 Block"
                        FeeRateType.FOUR_BLOCKS -> "6 Block"
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun checkNewBlocks() {
        try {
            val settings = settingsRepository.settings.first()

            // Skip if neither notification is enabled
            if (!settings.newBlockNotificationEnabled && !settings.specificBlockNotificationEnabled) {
                return
            }

            val blockHeight = api.getBlockHeight()
            if (blockHeight.isSuccessful) {
                val currentHeight = blockHeight.body()
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
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Mempal")
            .setContentText("Monitoring Bitcoin network")
            .setSmallIcon(R.drawable.ic_cube)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Mempal Notifications"
            val descriptionText = "Bitcoin network monitoring notifications"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, content: String) {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_cube)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager =
            getSystemService(NotificationManager::class.java)

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        monitoringJobs.values.forEach { it.cancel() }
        monitoringJobs.clear()
        serviceScope.cancel()
        NetworkClient.cleanup()

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