package com.example.mempal.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.example.mempal.MainActivity
import com.example.mempal.api.NetworkClient
import com.example.mempal.repository.SettingsRepository

object WidgetUtils {
    private var lastTapTime = 0L
    private const val DOUBLE_TAP_TIMEOUT = 500L // ms
    private var serviceInitialized = false
    private var isFirstTap = true

    /**
     * Check if a tap event is actually a double-tap
     * @return true if this is the second tap in a double-tap sequence
     */
    fun isDoubleTap(): Boolean {
        val currentTime = SystemClock.elapsedRealtime()
        val timeSinceLastTap = currentTime - lastTapTime
        
        // Safety check - if it's been a very long time since last tap (30 seconds),
        // reset the state to avoid getting stuck
        if (timeSinceLastTap > 30000) {
            resetTapState()
        }
        
        if (isFirstTap) {
            // This is the first tap in a potential double-tap sequence
            isFirstTap = false
            lastTapTime = currentTime
            return false
        } else if (timeSinceLastTap < DOUBLE_TAP_TIMEOUT) {
            // This is the second tap, within the timeout window
            // Reset for next sequence
            resetTapState()
            return true
        } else {
            // Too much time has passed, start a new sequence
            lastTapTime = currentTime
            // Keep isFirstTap as false since this is a new first tap
            return false
        }
    }
    
    /**
     * Reset the tap state to initial values
     * This can be called explicitly when network errors occur to ensure
     * tap detection works properly on the next attempt
     */
    fun resetTapState() {
        isFirstTap = true
        lastTapTime = 0L
    }
    
    /**
     * Get a PendingIntent to launch the main app
     */
    fun getLaunchAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
    
    /**
     * Ensure essential services are initialized for widget updates
     * This is important when the app is killed but widgets need to refresh
     */
    @Synchronized
    fun ensureInitialized(context: Context) {
        if (!serviceInitialized) {
            try {
                // Initialize settings repository
                SettingsRepository.getInstance(context)
                
                // Try to initialize network client if needed
                if (!NetworkClient.isInitialized.value) {
                    try {
                        NetworkClient.initialize(context)
                    } catch (e: Exception) {
                        // If main client fails, we'll fall back to WidgetNetworkClient
                        e.printStackTrace()
                    }
                }
                
                serviceInitialized = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
} 