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
        private const val DEFAULT_UPDATE_FREQUENCY = 15L // 15 minutes
        private const val KEY_NOTIFICATION_TIME_UNIT = "notification_time_unit"
        private const val DEFAULT_TIME_UNIT = "minutes"
        private const val KEY_SERVER_NEEDS_RESTART = "server_needs_restart"
        private const val KEY_SAVED_SERVERS = "saved_servers"

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

        private const val KEY_VISIBLE_CARDS = "visible_cards"
        private val DEFAULT_VISIBLE_CARDS = setOf("Block Height", "Mempool Size", "Fee Rates", "Fee Distribution")

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
        prefs.edit()
            .putString(KEY_API_URL, url)
            .putBoolean(KEY_SERVER_NEEDS_RESTART, true)
            .apply()
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

    fun getNotificationTimeUnit(): String {
        return prefs.getString(KEY_NOTIFICATION_TIME_UNIT, DEFAULT_TIME_UNIT) ?: DEFAULT_TIME_UNIT
    }

    fun saveNotificationTimeUnit(timeUnit: String) {
        prefs.edit().putString(KEY_NOTIFICATION_TIME_UNIT, timeUnit).apply()
    }

    private fun loadNotificationSettings(): NotificationSettings {
        return NotificationSettings(
            blockNotificationsEnabled = prefs.getBoolean(KEY_BLOCK_NOTIFICATIONS_ENABLED, false),
            blockCheckFrequency = if (prefs.contains(KEY_BLOCK_CHECK_FREQUENCY)) prefs.getInt(KEY_BLOCK_CHECK_FREQUENCY, 0) else 0,
            newBlockNotificationEnabled = prefs.getBoolean(KEY_NEW_BLOCK_NOTIFICATIONS_ENABLED, false),
            newBlockCheckFrequency = if (prefs.contains(KEY_NEW_BLOCK_CHECK_FREQUENCY)) prefs.getInt(KEY_NEW_BLOCK_CHECK_FREQUENCY, 0) else 0,
            specificBlockNotificationEnabled = prefs.getBoolean(KEY_SPECIFIC_BLOCK_NOTIFICATIONS_ENABLED, false),
            specificBlockCheckFrequency = if (prefs.contains(KEY_SPECIFIC_BLOCK_CHECK_FREQUENCY)) prefs.getInt(KEY_SPECIFIC_BLOCK_CHECK_FREQUENCY, 0) else 0,
            targetBlockHeight = if (prefs.contains(KEY_TARGET_BLOCK_HEIGHT)) prefs.getInt(KEY_TARGET_BLOCK_HEIGHT, -1) else null,
            mempoolSizeNotificationsEnabled = prefs.getBoolean(KEY_MEMPOOL_SIZE_NOTIFICATIONS_ENABLED, false),
            mempoolCheckFrequency = if (prefs.contains(KEY_MEMPOOL_CHECK_FREQUENCY)) prefs.getInt(KEY_MEMPOOL_CHECK_FREQUENCY, 0) else 0,
            mempoolSizeThreshold = if (prefs.contains(KEY_MEMPOOL_SIZE_THRESHOLD)) prefs.getFloat(KEY_MEMPOOL_SIZE_THRESHOLD, 0f) else 0f,
            mempoolSizeAboveThreshold = prefs.getBoolean(KEY_MEMPOOL_SIZE_ABOVE_THRESHOLD, false),
            feeRatesNotificationsEnabled = prefs.getBoolean(KEY_FEE_RATES_NOTIFICATIONS_ENABLED, false),
            feeRatesCheckFrequency = if (prefs.contains(KEY_FEE_RATES_CHECK_FREQUENCY)) prefs.getInt(KEY_FEE_RATES_CHECK_FREQUENCY, 0) else 0,
            selectedFeeRateType = FeeRateType.entries[prefs.getInt(KEY_SELECTED_FEE_RATE_TYPE, 0)],
            feeRateThreshold = if (prefs.contains(KEY_FEE_RATE_THRESHOLD)) prefs.getInt(KEY_FEE_RATE_THRESHOLD, 0) else 0,
            feeRateAboveThreshold = prefs.getBoolean(KEY_FEE_RATE_ABOVE_THRESHOLD, false),
            txConfirmationEnabled = prefs.getBoolean(KEY_TX_CONFIRMATION_ENABLED, false),
            txConfirmationFrequency = if (prefs.contains(KEY_TX_CONFIRMATION_FREQUENCY)) prefs.getInt(KEY_TX_CONFIRMATION_FREQUENCY, 0) else 0,
            transactionId = prefs.getString(KEY_TRANSACTION_ID, "") ?: ""
        )
    }

    fun updateSettings(settings: NotificationSettings) {
        _settings.value = settings

        // Save all notification settings except service state
        prefs.edit().apply {
            putBoolean(KEY_BLOCK_NOTIFICATIONS_ENABLED, settings.blockNotificationsEnabled)
            if (settings.blockCheckFrequency == 0) remove(KEY_BLOCK_CHECK_FREQUENCY) else putInt(KEY_BLOCK_CHECK_FREQUENCY, settings.blockCheckFrequency)
            putBoolean(KEY_NEW_BLOCK_NOTIFICATIONS_ENABLED, settings.newBlockNotificationEnabled)
            if (settings.newBlockCheckFrequency == 0) remove(KEY_NEW_BLOCK_CHECK_FREQUENCY) else putInt(KEY_NEW_BLOCK_CHECK_FREQUENCY, settings.newBlockCheckFrequency)
            putBoolean(KEY_SPECIFIC_BLOCK_NOTIFICATIONS_ENABLED, settings.specificBlockNotificationEnabled)
            if (settings.specificBlockCheckFrequency == 0) remove(KEY_SPECIFIC_BLOCK_CHECK_FREQUENCY) else putInt(KEY_SPECIFIC_BLOCK_CHECK_FREQUENCY, settings.specificBlockCheckFrequency)
            settings.targetBlockHeight?.let { putInt(KEY_TARGET_BLOCK_HEIGHT, it) } ?: remove(KEY_TARGET_BLOCK_HEIGHT)
            putBoolean(KEY_MEMPOOL_SIZE_NOTIFICATIONS_ENABLED, settings.mempoolSizeNotificationsEnabled)
            if (settings.mempoolCheckFrequency == 0) remove(KEY_MEMPOOL_CHECK_FREQUENCY) else putInt(KEY_MEMPOOL_CHECK_FREQUENCY, settings.mempoolCheckFrequency)
            if (settings.mempoolSizeThreshold == 0f) remove(KEY_MEMPOOL_SIZE_THRESHOLD) else putFloat(KEY_MEMPOOL_SIZE_THRESHOLD, settings.mempoolSizeThreshold)
            putBoolean(KEY_MEMPOOL_SIZE_ABOVE_THRESHOLD, settings.mempoolSizeAboveThreshold)
            putBoolean(KEY_FEE_RATES_NOTIFICATIONS_ENABLED, settings.feeRatesNotificationsEnabled)
            if (settings.feeRatesCheckFrequency == 0) remove(KEY_FEE_RATES_CHECK_FREQUENCY) else putInt(KEY_FEE_RATES_CHECK_FREQUENCY, settings.feeRatesCheckFrequency)
            putInt(KEY_SELECTED_FEE_RATE_TYPE, settings.selectedFeeRateType.ordinal)
            if (settings.feeRateThreshold == 0) remove(KEY_FEE_RATE_THRESHOLD) else putInt(KEY_FEE_RATE_THRESHOLD, settings.feeRateThreshold)
            putBoolean(KEY_FEE_RATE_ABOVE_THRESHOLD, settings.feeRateAboveThreshold)
            putBoolean(KEY_TX_CONFIRMATION_ENABLED, settings.txConfirmationEnabled)
            if (settings.txConfirmationFrequency == 0) remove(KEY_TX_CONFIRMATION_FREQUENCY) else putInt(KEY_TX_CONFIRMATION_FREQUENCY, settings.txConfirmationFrequency)
            putString(KEY_TRANSACTION_ID, settings.transactionId)
        }.apply()
    }

    fun clearServerRestartFlag() {
        prefs.edit().putBoolean(KEY_SERVER_NEEDS_RESTART, false).apply()
    }

    fun needsRestartForServer(): Boolean {
        return prefs.getBoolean(KEY_SERVER_NEEDS_RESTART, false)
    }

    fun getSavedServers(): Set<String> {
        return prefs.getStringSet(KEY_SAVED_SERVERS, setOf()) ?: setOf()
    }

    fun addSavedServer(url: String) {
        val currentServers = getSavedServers().toMutableSet()
        currentServers.add(url.trimEnd('/'))
        prefs.edit().putStringSet(KEY_SAVED_SERVERS, currentServers).apply()
    }

    fun removeSavedServer(url: String) {
        val currentServers = getSavedServers().toMutableSet()
        currentServers.remove(url)
        prefs.edit().putStringSet(KEY_SAVED_SERVERS, currentServers).apply()
    }

    fun getVisibleCards(): Set<String> {
        return prefs.getStringSet(KEY_VISIBLE_CARDS, DEFAULT_VISIBLE_CARDS) ?: DEFAULT_VISIBLE_CARDS
    }

    fun saveVisibleCards(visibleCards: Set<String>) {
        prefs.edit().putStringSet(KEY_VISIBLE_CARDS, visibleCards).apply()
    }
} 