package com.example.mempal.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.mempal.MainActivity
import com.example.mempal.R
import com.example.mempal.api.NetworkClient
import com.example.mempal.repository.SettingsRepository
import com.example.mempal.model.FeeRateType
import com.example.mempal.model.NotificationSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class NotificationService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var settingsRepository: SettingsRepository
    private val api = NetworkClient.mempoolApi
    private val channelId = "mempal_notifications"
    private var lastBlockHeight: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository.getInstance(applicationContext)
        createNotificationChannel()
        startForeground(1, createForegroundNotification())
        startMonitoring()
    }

    private fun startMonitoring() {
        serviceScope.launch {
            while (isActive) {
                val settings = settingsRepository.settings.first()

                if (settings.mempoolSizeNotificationsEnabled) {
                    checkMempoolSize(settings.mempoolSizeThreshold)
                }

                if (settings.feeRatesNotificationsEnabled) {
                    checkFeeRates(settings.selectedFeeRateType, settings.feeRateThreshold)
                }

                if (settings.blockNotificationsEnabled) {
                    checkNewBlocks()
                }

                val shortestDelay = minOf(
                    if (settings.mempoolSizeNotificationsEnabled) settings.mempoolCheckFrequency else Int.MAX_VALUE,
                    if (settings.feeRatesNotificationsEnabled) settings.feeRatesCheckFrequency else Int.MAX_VALUE,
                    if (settings.blockNotificationsEnabled) settings.blockCheckFrequency else Int.MAX_VALUE,
                    1
                )

                delay(shortestDelay * 60 * 1000L)
            }
        }
    }

    private suspend fun checkMempoolSize(threshold: Float) {
        try {
            val mempoolInfo = api.getMempoolInfo()
            if (mempoolInfo.isSuccessful) {
                val currentSize = mempoolInfo.body()?.vsize?.toFloat()?.div(1_000_000f) ?: return
                if (currentSize < threshold) {
                    showNotification(
                        "Mempool Size Alert",
                        "Current mempool size is now below ${String.format("%.2f", currentSize)} vMB"
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun checkFeeRates(feeRateType: FeeRateType, threshold: Int) {
        try {
            val feeRates = api.getFeeRates()
            if (feeRates.isSuccessful) {
                val rates = feeRates.body() ?: return
                val currentRate = when (feeRateType) {
                    FeeRateType.NEXT_BLOCK -> rates.fastestFee
                    FeeRateType.TWO_BLOCKS -> rates.halfHourFee
                    FeeRateType.FOUR_BLOCKS -> rates.hourFee
                }
                if (currentRate < threshold) {
                    showNotification(
                        "Fee Rate Alert",
                        "Current fee rate is now below $currentRate sat/vB"
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun checkNewBlocks() {
        try {
            val blockHeight = api.getBlockHeight()
            if (blockHeight.isSuccessful) {
                val currentHeight = blockHeight.body()
                if (lastBlockHeight != null && currentHeight != null && currentHeight > lastBlockHeight!!) {
                    showNotification(
                        "New Block Mined",
                        "Block #$currentHeight has been mined"
                    )
                }
                lastBlockHeight = currentHeight
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
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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

        val notificationManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getSystemService(NotificationManager::class.java)
        } else {
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        settingsRepository.updateSettings(NotificationSettings())
    }
}