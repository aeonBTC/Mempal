package com.example.mempal.tor

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.torproject.jni.TorService
import java.io.File

enum class TorStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

class TorManager private constructor() {
    private var torService: TorService? = null
    private val _torStatus = MutableStateFlow(TorStatus.DISCONNECTED)
    val torStatus: StateFlow<TorStatus> = _torStatus
    private var dataDir: File? = null
    private lateinit var prefs: SharedPreferences
    private val _proxyReady = MutableStateFlow(false)
    val proxyReady: StateFlow<Boolean> = _proxyReady
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        @Volatile
        private var instance: TorManager? = null
        private const val ACTION_START = "org.torproject.android.intent.action.START"
        private const val ACTION_STOP = "org.torproject.android.intent.action.STOP"
        private const val PREFS_NAME = "tor_prefs"
        private const val KEY_TOR_ENABLED = "tor_enabled"

        fun getInstance(): TorManager {
            return instance ?: synchronized(this) {
                instance ?: TorManager().also { instance = it }
            }
        }
    }

    fun initialize(context: Context) {
        try {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val dir = File(context.filesDir, "tor")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            dataDir = dir
            torService = TorService()

            // Restore previous state
            if (prefs.getBoolean(KEY_TOR_ENABLED, false)) {
                startTor(context)
            }
        } catch (e: Exception) {
            _torStatus.value = TorStatus.ERROR
            e.printStackTrace()
        }
    }

    fun startTor(context: Context) {
        try {
            _torStatus.value = TorStatus.CONNECTING
            val intent = Intent(context, TorService::class.java).apply {
                action = ACTION_START
                putExtra("directory", dataDir?.absolutePath)
            }
            context.startService(intent)

            scope.launch {
                delay(5100)
                _torStatus.value = TorStatus.CONNECTED
                _proxyReady.value = true
                prefs.edit().putBoolean(KEY_TOR_ENABLED, true).apply()
            }
        } catch (e: Exception) {
            _torStatus.value = TorStatus.ERROR
            _proxyReady.value = false
            e.printStackTrace()
        }
    }

    fun stopTor(context: Context) {
        try {
            val intent = Intent(context, TorService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
            _torStatus.value = TorStatus.DISCONNECTED
            _proxyReady.value = false
            prefs.edit().putBoolean(KEY_TOR_ENABLED, false).apply()
        } catch (e: Exception) {
            _torStatus.value = TorStatus.ERROR
            e.printStackTrace()
        }
    }

    fun isTorEnabled(): Boolean {
        return prefs.getBoolean(KEY_TOR_ENABLED, false)
    }
}