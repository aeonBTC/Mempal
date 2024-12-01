package com.example.mempal.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.mempal.model.NotificationSettings
import com.example.mempal.model.FeeRateType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

class SettingsRepository private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val prefs: SharedPreferences = applicationContext.getSharedPreferences(
        "mempal_settings",
        Context.MODE_PRIVATE
    )

    private val _settings = MutableStateFlow(loadNotificationSettings())
    val settings: StateFlow<NotificationSettings> = _settings

    companion object {
        @Volatile
        private var instance: WeakReference<SettingsRepository>? = null
        private const val KEY_API_URL = "api_url"
        private const val KEY_UPDATE_FREQUENCY = "widget_update_frequency"
        private const val DEFAULT_API_URL = "https://mempool.space"
        private const val DEFAULT_UPDATE_FREQUENCY = 30L // 30 minutes

        // Notification Settings Keys
        private const val KEY_BLOCK_NOTIFICATIONS_ENABLED = "block_notifications_enabled"
        private const val KEY_BLOCK_CHECK_FREQUENCY = "block_check_frequency"
        private const val KEY_NEW_BLOCK_NOTIFICATIONS_ENABLED = "new_block_notifications_enabled"
        private const val KEY_NEW_BLOCK_CHECK_FREQUENCY = "new_block_check_frequency"
        private const val KEY_SPECIFIC_BLOCK_NOTIFICATIONS_ENABLED = "specific_block_notifications_enabled"
        private const val KEY_SPECIFIC_BLOCK_CHECK_FREQUENCY = "specific_block_check_frequency"
        private const val KEY_TARGET_BLOCK_HEIGHT = "target_block_height"
        private const val KEY_MEMPOOL_SIZE_NOTIFICATIONS_ENABLED = "mempool_size_notifications_enabled"
        private const val KEY_MEMPOOL_CHECK_FREQUENCY = "mempool_check_frequency"
        private const val KEY_MEMPOOL_SIZE_THRESHOLD = "mempool_size_threshold"
        private const val KEY_MEMPOOL_SIZE_ABOVE_THRESHOLD = "mempool_size_above_threshold"
        private const val KEY_FEE_RATES_NOTIFICATIONS_ENABLED = "fee_rates_notifications_enabled"
        private const val KEY_FEE_RATES_CHECK_FREQUENCY = "fee_rates_check_frequency"
        private const val KEY_SELECTED_FEE_RATE_TYPE = "selected_fee_rate_type"
        private const val KEY_FEE_RATE_THRESHOLD = "fee_rate_threshold"
        private const val KEY_FEE_RATE_ABOVE_THRESHOLD = "fee_rate_above_threshold"
        private const val KEY_TX_CONFIRMATION_ENABLED = "tx_confirmation_enabled"
        private const val KEY_TX_CONFIRMATION_FREQUENCY = "tx_confirmation_frequency"
        private const val KEY_TRANSACTION_ID = "transaction_id"

        fun getInstance(context: Context): SettingsRepository {
            val currentInstance = instance?.get()
            if (currentInstance != null) {
                return currentInstance
            }

            return synchronized(this) {
                val newInstance = SettingsRepository(context)
                instance = WeakReference(newInstance)
                newInstance
            }
        }

        fun cleanup() {
            synchronized(this) {
                instance?.clear()
                instance = null
            }
        }
    }

    fun getApiUrl(): String {
        return prefs.getString(KEY_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
    }

    fun saveApiUrl(url: String) {
        prefs.edit().putString(KEY_API_URL, url).apply()
    }

    fun getUpdateFrequency(): Long {
        return try {
            prefs.getLong(KEY_UPDATE_FREQUENCY, DEFAULT_UPDATE_FREQUENCY)
        } catch (_: ClassCastException) {
            prefs.getInt(KEY_UPDATE_FREQUENCY, DEFAULT_UPDATE_FREQUENCY.toInt()).toLong()
        }
    }

    fun saveUpdateFrequency(minutes: Long) {
        prefs.edit().putLong(KEY_UPDATE_FREQUENCY, minutes).apply()
    }

    private fun loadNotificationSettings(): NotificationSettings {
        return NotificationSettings(
            blockNotificationsEnabled = prefs.getBoolean(KEY_BLOCK_NOTIFICATIONS_ENABLED, false),
            blockCheckFrequency = prefs.getInt(KEY_BLOCK_CHECK_FREQUENCY, 10),
            newBlockNotificationEnabled = prefs.getBoolean(KEY_NEW_BLOCK_NOTIFICATIONS_ENABLED, false),
            newBlockCheckFrequency = prefs.getInt(KEY_NEW_BLOCK_CHECK_FREQUENCY, 10),
            specificBlockNotificationEnabled = prefs.getBoolean(KEY_SPECIFIC_BLOCK_NOTIFICATIONS_ENABLED, false),
            specificBlockCheckFrequency = prefs.getInt(KEY_SPECIFIC_BLOCK_CHECK_FREQUENCY, 10),
            targetBlockHeight = if (prefs.contains(KEY_TARGET_BLOCK_HEIGHT)) prefs.getInt(KEY_TARGET_BLOCK_HEIGHT, -1) else null,
            mempoolSizeNotificationsEnabled = prefs.getBoolean(KEY_MEMPOOL_SIZE_NOTIFICATIONS_ENABLED, false),
            mempoolCheckFrequency = prefs.getInt(KEY_MEMPOOL_CHECK_FREQUENCY, 10),
            mempoolSizeThreshold = prefs.getFloat(KEY_MEMPOOL_SIZE_THRESHOLD, 10f),
            mempoolSizeAboveThreshold = prefs.getBoolean(KEY_MEMPOOL_SIZE_ABOVE_THRESHOLD, false),
            feeRatesNotificationsEnabled = prefs.getBoolean(KEY_FEE_RATES_NOTIFICATIONS_ENABLED, false),
            feeRatesCheckFrequency = prefs.getInt(KEY_FEE_RATES_CHECK_FREQUENCY, 10),
            selectedFeeRateType = FeeRateType.entries[prefs.getInt(KEY_SELECTED_FEE_RATE_TYPE, 0)],
            feeRateThreshold = prefs.getInt(KEY_FEE_RATE_THRESHOLD, 1),
            feeRateAboveThreshold = prefs.getBoolean(KEY_FEE_RATE_ABOVE_THRESHOLD, false),
            txConfirmationEnabled = prefs.getBoolean(KEY_TX_CONFIRMATION_ENABLED, false),
            txConfirmationFrequency = prefs.getInt(KEY_TX_CONFIRMATION_FREQUENCY, 10),
            transactionId = prefs.getString(KEY_TRANSACTION_ID, "") ?: ""
        )
    }

    fun updateSettings(settings: NotificationSettings) {
        _settings.value = settings

        // Save all notification settings except service state
        prefs.edit().apply {
            putBoolean(KEY_BLOCK_NOTIFICATIONS_ENABLED, settings.blockNotificationsEnabled)
            putInt(KEY_BLOCK_CHECK_FREQUENCY, settings.blockCheckFrequency)
            putBoolean(KEY_NEW_BLOCK_NOTIFICATIONS_ENABLED, settings.newBlockNotificationEnabled)
            putInt(KEY_NEW_BLOCK_CHECK_FREQUENCY, settings.newBlockCheckFrequency)
            putBoolean(KEY_SPECIFIC_BLOCK_NOTIFICATIONS_ENABLED, settings.specificBlockNotificationEnabled)
            putInt(KEY_SPECIFIC_BLOCK_CHECK_FREQUENCY, settings.specificBlockCheckFrequency)
            settings.targetBlockHeight?.let { putInt(KEY_TARGET_BLOCK_HEIGHT, it) } ?: remove(KEY_TARGET_BLOCK_HEIGHT)
            putBoolean(KEY_MEMPOOL_SIZE_NOTIFICATIONS_ENABLED, settings.mempoolSizeNotificationsEnabled)
            putInt(KEY_MEMPOOL_CHECK_FREQUENCY, settings.mempoolCheckFrequency)
            putFloat(KEY_MEMPOOL_SIZE_THRESHOLD, settings.mempoolSizeThreshold)
            putBoolean(KEY_MEMPOOL_SIZE_ABOVE_THRESHOLD, settings.mempoolSizeAboveThreshold)
            putBoolean(KEY_FEE_RATES_NOTIFICATIONS_ENABLED, settings.feeRatesNotificationsEnabled)
            putInt(KEY_FEE_RATES_CHECK_FREQUENCY, settings.feeRatesCheckFrequency)
            putInt(KEY_SELECTED_FEE_RATE_TYPE, settings.selectedFeeRateType.ordinal)
            putInt(KEY_FEE_RATE_THRESHOLD, settings.feeRateThreshold)
            putBoolean(KEY_FEE_RATE_ABOVE_THRESHOLD, settings.feeRateAboveThreshold)
            putBoolean(KEY_TX_CONFIRMATION_ENABLED, settings.txConfirmationEnabled)
            putInt(KEY_TX_CONFIRMATION_FREQUENCY, settings.txConfirmationFrequency)
            putString(KEY_TRANSACTION_ID, settings.transactionId)
        }.apply()
    }
} 