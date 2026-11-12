package com.example.mempal.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.mempal.model.NotificationSettings
import com.example.mempal.model.FeeRateType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
        private var instance: SettingsRepository? = null
        private const val KEY_API_URL = "api_url"
        private const val KEY_UPDATE_FREQUENCY = "widget_update_frequency"
        private const val DEFAULT_API_URL = "https://mempool.space"
        private const val DEFAULT_UPDATE_FREQUENCY = 30L // 30 minutes
        private const val KEY_NOTIFICATION_TIME_UNIT = "notification_time_unit"
        private const val DEFAULT_TIME_UNIT = "minutes"
        private const val KEY_SERVER_NEEDS_RESTART = "server_needs_restart"
        private const val KEY_SAVED_SERVERS = "saved_servers"

        // Notification Settings Keys
        private const val KEY_BLOCK_NOTIFICATIONS_ENABLED = "block_notifications_enabled"
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
        private const val KEY_USE_PRECISE_FEES = "use_precise_fees"
        private const val KEY_TX_CONFIRMATION_ENABLED = "tx_confirmation_enabled"
        private const val KEY_TX_CONFIRMATION_FREQUENCY = "tx_confirmation_frequency"
        private const val KEY_TX_CONFIRMATIONS_TARGET = "tx_confirmations_target"
        private const val DEFAULT_TX_CONFIRMATIONS_TARGET = 1
        private const val KEY_TRANSACTION_ID = "transaction_id"
        private const val KEY_IS_SERVICE_ENABLED = "is_service_enabled"
        private const val KEY_HAS_NOTIFIED_FOR_NEW_BLOCK = "has_notified_for_new_block"
        private const val KEY_HAS_NOTIFIED_FOR_TARGET_BLOCK = "has_notified_for_target_block"
        private const val KEY_HAS_NOTIFIED_FOR_CURRENT_TX = "has_notified_for_current_tx"
        private const val KEY_HAS_NOTIFIED_FOR_MEMPOOL_SIZE = "has_notified_for_mempool_size"
        private const val KEY_HAS_NOTIFIED_FOR_FEE_RATE = "has_notified_for_fee_rate"

        private const val KEY_VISIBLE_CARDS = "visible_cards"
        private const val KEY_CARD_ORDER = "card_order"
        private val DEFAULT_VISIBLE_CARDS = setOf(
            "Block Height",
            "Hashrate",
            "Mempool Size",
            "Fee Rates",
            "Fee Distribution"
        )
        private val DEFAULT_CARD_ORDER = listOf(
            "Block Height",
            "Hashrate",
            "Mempool Size",
            "Fee Rates",
            "Fee Distribution"
        )
        private const val KEY_IS_FIRST_LAUNCH = "is_first_launch"
        private const val KEY_FEE_DISTRIBUTION_EXPANDED = "fee_distribution_expanded"

        fun getInstance(context: Context): SettingsRepository {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
        }
    }

    fun getApiUrl(): String {
        return prefs.getString(KEY_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
    }

    fun saveApiUrl(url: String) {
        prefs.edit {
            putString(KEY_API_URL, url)
            putBoolean(KEY_SERVER_NEEDS_RESTART, true)
        }
    }

    fun getUpdateFrequency(): Long {
        return try {
            prefs.getLong(KEY_UPDATE_FREQUENCY, DEFAULT_UPDATE_FREQUENCY)
        } catch (_: ClassCastException) {
            prefs.getInt(KEY_UPDATE_FREQUENCY, DEFAULT_UPDATE_FREQUENCY.toInt()).toLong()
        }
    }

    fun saveUpdateFrequency(minutes: Long) {
        prefs.edit {
            putLong(KEY_UPDATE_FREQUENCY, minutes)
        }
    }

    fun getNotificationTimeUnit(): String {
        return prefs.getString(KEY_NOTIFICATION_TIME_UNIT, DEFAULT_TIME_UNIT) ?: DEFAULT_TIME_UNIT
    }

    fun saveNotificationTimeUnit(timeUnit: String) {
        prefs.edit {
            putString(KEY_NOTIFICATION_TIME_UNIT, timeUnit)
        }
    }

    private fun loadNotificationSettings(): NotificationSettings {
        // Read all prefs at once for efficiency instead of multiple individual reads
        val allPrefs = prefs.all
        
        // Helper functions to safely extract values from the snapshot
        fun getBoolean(key: String, default: Boolean = false): Boolean = 
            (allPrefs[key] as? Boolean) ?: default
        
        fun getInt(key: String, default: Int = 0): Int = 
            (allPrefs[key] as? Int) ?: default
        
        fun getIntOrNull(key: String): Int? = 
            allPrefs[key] as? Int
        
        fun getFloat(key: String, default: Float = 0f): Float = 
            (allPrefs[key] as? Float) ?: default
        
        fun getString(key: String, default: String = ""): String = 
            (allPrefs[key] as? String) ?: default
        
        // Handle feeRateThreshold with migration from Int to Float
        val feeRateThreshold: Double = when (val value = allPrefs[KEY_FEE_RATE_THRESHOLD]) {
            is Float -> value.toDouble()
            is Int -> {
                // Migrate from Int to Float for future use
                value.toDouble().also { migratedValue ->
                    prefs.edit { putFloat(KEY_FEE_RATE_THRESHOLD, migratedValue.toFloat()) }
                }
            }
            else -> 0.0
        }
        
        return NotificationSettings(
            blockNotificationsEnabled = getBoolean(KEY_BLOCK_NOTIFICATIONS_ENABLED),
            newBlockNotificationEnabled = getBoolean(KEY_NEW_BLOCK_NOTIFICATIONS_ENABLED),
            newBlockCheckFrequency = getInt(KEY_NEW_BLOCK_CHECK_FREQUENCY),
            specificBlockNotificationEnabled = getBoolean(KEY_SPECIFIC_BLOCK_NOTIFICATIONS_ENABLED),
            specificBlockCheckFrequency = getInt(KEY_SPECIFIC_BLOCK_CHECK_FREQUENCY),
            targetBlockHeight = getIntOrNull(KEY_TARGET_BLOCK_HEIGHT),
            mempoolSizeNotificationsEnabled = getBoolean(KEY_MEMPOOL_SIZE_NOTIFICATIONS_ENABLED),
            mempoolCheckFrequency = getInt(KEY_MEMPOOL_CHECK_FREQUENCY),
            mempoolSizeThreshold = getFloat(KEY_MEMPOOL_SIZE_THRESHOLD),
            mempoolSizeAboveThreshold = getBoolean(KEY_MEMPOOL_SIZE_ABOVE_THRESHOLD),
            feeRatesNotificationsEnabled = getBoolean(KEY_FEE_RATES_NOTIFICATIONS_ENABLED),
            feeRatesCheckFrequency = getInt(KEY_FEE_RATES_CHECK_FREQUENCY),
            selectedFeeRateType = FeeRateType.entries.getOrElse(getInt(KEY_SELECTED_FEE_RATE_TYPE)) {
                FeeRateType.NEXT_BLOCK
            },
            feeRateThreshold = feeRateThreshold,
            feeRateAboveThreshold = getBoolean(KEY_FEE_RATE_ABOVE_THRESHOLD),
            usePreciseFees = getBoolean(KEY_USE_PRECISE_FEES),
            txConfirmationEnabled = getBoolean(KEY_TX_CONFIRMATION_ENABLED),
            txConfirmationFrequency = getInt(KEY_TX_CONFIRMATION_FREQUENCY),
            targetConfirmations = getInt(KEY_TX_CONFIRMATIONS_TARGET, DEFAULT_TX_CONFIRMATIONS_TARGET)
                .coerceAtLeast(DEFAULT_TX_CONFIRMATIONS_TARGET),
            transactionId = getString(KEY_TRANSACTION_ID),
            isServiceEnabled = getBoolean(KEY_IS_SERVICE_ENABLED),
            hasNotifiedForNewBlock = getBoolean(KEY_HAS_NOTIFIED_FOR_NEW_BLOCK),
            hasNotifiedForTargetBlock = getBoolean(KEY_HAS_NOTIFIED_FOR_TARGET_BLOCK),
            hasNotifiedForCurrentTx = getBoolean(KEY_HAS_NOTIFIED_FOR_CURRENT_TX),
            hasNotifiedForMempoolSize = getBoolean(KEY_HAS_NOTIFIED_FOR_MEMPOOL_SIZE),
            hasNotifiedForFeeRate = getBoolean(KEY_HAS_NOTIFIED_FOR_FEE_RATE)
        )
    }

    fun updateSettings(settings: NotificationSettings) {
        _settings.value = settings

        prefs.edit {
            putBoolean(KEY_IS_SERVICE_ENABLED, settings.isServiceEnabled)
            putBoolean(KEY_HAS_NOTIFIED_FOR_NEW_BLOCK, settings.hasNotifiedForNewBlock)
            putBoolean(KEY_HAS_NOTIFIED_FOR_TARGET_BLOCK, settings.hasNotifiedForTargetBlock)
            putBoolean(KEY_HAS_NOTIFIED_FOR_CURRENT_TX, settings.hasNotifiedForCurrentTx)
            putBoolean(KEY_HAS_NOTIFIED_FOR_MEMPOOL_SIZE, settings.hasNotifiedForMempoolSize)
            putBoolean(KEY_HAS_NOTIFIED_FOR_FEE_RATE, settings.hasNotifiedForFeeRate)
            putBoolean(KEY_BLOCK_NOTIFICATIONS_ENABLED, settings.blockNotificationsEnabled)
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
            if (settings.feeRateThreshold == 0.0) remove(KEY_FEE_RATE_THRESHOLD) else putFloat(KEY_FEE_RATE_THRESHOLD, settings.feeRateThreshold.toFloat())
            putBoolean(KEY_FEE_RATE_ABOVE_THRESHOLD, settings.feeRateAboveThreshold)
            putBoolean(KEY_USE_PRECISE_FEES, settings.usePreciseFees)
            putBoolean(KEY_TX_CONFIRMATION_ENABLED, settings.txConfirmationEnabled)
            if (settings.txConfirmationFrequency == 0) remove(KEY_TX_CONFIRMATION_FREQUENCY) else putInt(KEY_TX_CONFIRMATION_FREQUENCY, settings.txConfirmationFrequency)
            if (settings.targetConfirmations <= DEFAULT_TX_CONFIRMATIONS_TARGET) {
                remove(KEY_TX_CONFIRMATIONS_TARGET)
            } else {
                putInt(KEY_TX_CONFIRMATIONS_TARGET, settings.targetConfirmations)
            }
            putString(KEY_TRANSACTION_ID, settings.transactionId)
        }
    }

    fun clearServerRestartFlag() {
        prefs.edit {
            putBoolean(KEY_SERVER_NEEDS_RESTART, false)
        }
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
        prefs.edit {
            putStringSet(KEY_SAVED_SERVERS, currentServers)
        }
    }

    fun removeSavedServer(url: String) {
        val currentServers = getSavedServers().toMutableSet()
        currentServers.remove(url)
        prefs.edit {
            putStringSet(KEY_SAVED_SERVERS, currentServers)
        }
    }

    fun isFirstLaunch(): Boolean {
        return prefs.getBoolean(KEY_IS_FIRST_LAUNCH, true)
    }

    fun setFirstLaunchComplete() {
        prefs.edit {
            putBoolean(KEY_IS_FIRST_LAUNCH, false)
        }
    }

    fun getVisibleCards(): Set<String> {
        return prefs.getStringSet(KEY_VISIBLE_CARDS, DEFAULT_VISIBLE_CARDS) ?: DEFAULT_VISIBLE_CARDS
    }

    fun saveVisibleCards(visibleCards: Set<String>) {
        prefs.edit {
            putStringSet(KEY_VISIBLE_CARDS, visibleCards)
        }
    }

    fun getCardOrder(): List<String> {
        val orderString = prefs.getString(KEY_CARD_ORDER, null)
        return orderString?.split(",")?.filter { it.isNotEmpty() } ?: DEFAULT_CARD_ORDER
    }

    fun saveCardOrder(order: List<String>) {
        prefs.edit {
            putString(KEY_CARD_ORDER, order.joinToString(","))
        }
    }

    fun isFeeDistributionExpanded(): Boolean {
        return prefs.getBoolean(KEY_FEE_DISTRIBUTION_EXPANDED, false)
    }

    fun setFeeDistributionExpanded(expanded: Boolean) {
        prefs.edit {
            putBoolean(KEY_FEE_DISTRIBUTION_EXPANDED, expanded)
        }
    }
} 