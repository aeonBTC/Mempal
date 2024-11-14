package com.example.mempal.repository

import android.content.Context
import com.example.mempal.model.NotificationSettings
import com.example.mempal.model.FeeRateType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsRepository(context: Context) {
    private val _settings = MutableStateFlow(NotificationSettings())
    val settings: StateFlow<NotificationSettings> = _settings

    companion object {
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
        }
    }

    fun updateSettings(settings: NotificationSettings) {
        _settings.value = settings
    }
} 