package com.example.mempal.tor

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.torproject.jni.TorService
import java.io.File
import java.lang.ref.WeakReference

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
    private var prefsRef: WeakReference<SharedPreferences>? = null
    private val _proxyReady = MutableStateFlow(false)
    private var scope: CoroutineScope? = null
    private var isForegroundServiceRunning = false
    private var shouldBeTorEnabled = false
    private var connectionJob: Job? = null
    private val _torConnectionEvent = MutableSharedFlow<Boolean>()
    val torConnectionEvent: SharedFlow<Boolean> = _torConnectionEvent
    private var lastConnectionAttempt = 0L
    private var lastFailureMessage = 0L
    private var connectionAttempts = 0
    private val maxConnectionAttempts = 20
    private val minRetryDelay = 1500L
    private val maxRetryDelay = 20000L
    private val initialConnectionTimeout = 7000L
    private val minTimeBetweenFailures = 30000L
    private val maxInitialAttempts = 5
    private var isInInitialConnection = true
    
    // Pre-allocated InetSocketAddress to avoid creating new instances on each connection check
    private val torProxyAddress = java.net.InetSocketAddress("127.0.0.1", 9050)

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
            scope?.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefsRef = WeakReference(prefs)

            val dir = File(context.filesDir, "tor")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            dataDir = dir
            torService = TorService()

            shouldBeTorEnabled = prefs.getBoolean(KEY_TOR_ENABLED, false)

            if (shouldBeTorEnabled) {
                startTor(context)
            }
        } catch (e: Exception) {
            _torStatus.value = TorStatus.ERROR
            e.printStackTrace()
        }
    }

    private suspend fun emitConnectionEvent(connected: Boolean) {
        _torConnectionEvent.emit(connected)
    }

    fun startTor(context: Context) {
        try {
            shouldBeTorEnabled = true
            _torStatus.value = TorStatus.CONNECTING
            connectionAttempts = 0
            lastFailureMessage = 0L
            isInInitialConnection = true

            val intent = Intent(context, TorService::class.java).apply {
                action = ACTION_START
                putExtra("directory", dataDir?.absolutePath)
            }
            context.startService(intent)

            connectionJob?.cancel()
            connectionJob = scope?.launch {
                var currentDelay = minRetryDelay

                while (isActive) {
                    delay(if (connectionAttempts == 0) initialConnectionTimeout else currentDelay)
                    
                    if (connectionAttempts > 0) {
                        currentDelay = (currentDelay * 1.2).toLong().coerceAtMost(maxRetryDelay)
                    }

                    try {
                        withContext(Dispatchers.IO) {
                            val socket = java.net.Socket()
                            try {
                                socket.connect(torProxyAddress, 5000)
                                socket.close()
                                _torStatus.value = TorStatus.CONNECTED
                                _proxyReady.value = true
                                emitConnectionEvent(true)
                                connectionAttempts = 0
                                lastConnectionAttempt = System.currentTimeMillis()
                                lastFailureMessage = 0L
                                isInInitialConnection = false
                                return@withContext
                            } catch (e: Exception) {
                                socket.close()
                                throw e
                            }
                        }
                        break
                    } catch (_: Exception) {
                        connectionAttempts++
                        
                        if (isInInitialConnection && connectionAttempts <= maxInitialAttempts) {
                            _torStatus.value = TorStatus.CONNECTING
                            continue
                        }
                        
                        isInInitialConnection = false
                        val now = System.currentTimeMillis()
                        
                        if (connectionAttempts >= maxConnectionAttempts && 
                            !isInInitialConnection &&
                            (now - lastFailureMessage > minTimeBetweenFailures || lastFailureMessage == 0L)) {
                            connectionAttempts = maxConnectionAttempts / 2
                            emitConnectionEvent(false)
                            lastFailureMessage = now
                        }
                        
                        _torStatus.value = TorStatus.CONNECTING
                    }
                }
            }
        } catch (e: Exception) {
            _torStatus.value = TorStatus.ERROR
            _proxyReady.value = false
            scope?.launch { emitConnectionEvent(false) }
            e.printStackTrace()
        }
    }

    fun stopTor(context: Context) {
        try {
            shouldBeTorEnabled = false
            isInInitialConnection = true

            connectionJob?.cancel()
            connectionJob = null

            val intent = Intent(context, TorService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)

            stopForegroundService(context)

            _torStatus.value = TorStatus.DISCONNECTED
            _proxyReady.value = false
        } catch (e: Exception) {
            _torStatus.value = TorStatus.ERROR
            e.printStackTrace()
        }
    }

    fun startForegroundService(context: Context) {
        if (!isForegroundServiceRunning && torStatus.value == TorStatus.CONNECTED) {
            val foregroundIntent = Intent(context, TorForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(foregroundIntent)
            } else {
                context.startService(foregroundIntent)
            }
            isForegroundServiceRunning = true
        }
    }

    fun stopForegroundService(context: Context) {
        if (isForegroundServiceRunning) {
            val foregroundIntent = Intent(context, TorForegroundService::class.java)
            context.stopService(foregroundIntent)
            isForegroundServiceRunning = false
        }
    }

    fun isTorEnabled(): Boolean {
        return prefsRef?.get()?.getBoolean(KEY_TOR_ENABLED, false) == true
    }

    fun checkAndRestoreTorConnection(context: Context) {
        if (!shouldBeTorEnabled) return

        val now = System.currentTimeMillis()
        if (now - lastConnectionAttempt < (minRetryDelay / 2)) return

        scope?.launch {
            try {
                val torRunning = withContext(Dispatchers.IO) {
                    try {
                        val socket = java.net.Socket()
                        socket.connect(torProxyAddress, 500)
                        socket.close()
                        true
                    } catch (_: Exception) {
                        false
                    }
                }

                if (!torRunning) {
                    if (torStatus.value != TorStatus.CONNECTING) {
                        connectionAttempts = 0
                        lastFailureMessage = 0L
                        startTor(context)
                    }
                } else if (torStatus.value != TorStatus.CONNECTED) {
                    _torStatus.value = TorStatus.CONNECTED
                    _proxyReady.value = true
                    emitConnectionEvent(true)
                    connectionAttempts = 0
                    lastFailureMessage = 0L
                }
            } catch (_: Exception) {
                if (torStatus.value != TorStatus.CONNECTING) {
                    connectionAttempts = 0
                    lastFailureMessage = 0L
                    startTor(context)
                }
            }
        }
    }

    fun cleanup() {
        connectionJob?.cancel()
        connectionJob = null
        scope?.cancel()
        scope = null
    }

    fun saveTorState(enabled: Boolean) {
        prefsRef?.get()?.edit {
            putBoolean(KEY_TOR_ENABLED, enabled)
        }
    }
}