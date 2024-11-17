package com.example.mempal.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.mempal.model.NotificationSettings
import com.example.mempal.model.FeeRateType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsRepository(context: Context) {
    private val _settings = MutableStateFlow(NotificationSettings())
    val settings: StateFlow<NotificationSettings> = _settings

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "mempal_settings",
        Context.MODE_PRIVATE
    )

    companion object {
        private var instance: SettingsRepository? = null
        private const val KEY_API_URL = "api_url"
        private const val DEFAULT_API_URL = "https://mempool.space"

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
        }
    }

    fun getApiUrl(): String {
        return prefs.getString(KEY_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
    }

    fun saveApiUrl(url: String) {
        prefs.edit().putString(KEY_API_URL, url).apply()
    }

    fun updateSettings(settings: NotificationSettings) {
        _settings.value = settings
    }
} 