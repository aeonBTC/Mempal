package com.example.mempal.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Receiver to handle system boot events and package updates.
 * This helps ensure widgets continue to update after device reboots
 * or when the app is updated.
 * 
 * Uses goAsync() to properly handle asynchronous work in a BroadcastReceiver,
 * avoiding the deprecated GlobalScope pattern.
 */
class WidgetBootupReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "WidgetBootupReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received system event: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "System restart detected, rescheduling widget updates")
                
                // Use goAsync() for proper async handling in BroadcastReceiver
                // This gives us ~10 seconds to complete the work
                val pendingResult = goAsync()
                
                // Create a properly scoped coroutine that will finish the pending result
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        // Give the system a moment to stabilize after boot/update
                        delay(5000)

                        // Reschedule widget updates
                        WidgetUpdater.scheduleUpdates(context.applicationContext)

                        // Request an immediate update to refresh widgets
                        delay(2000)
                        WidgetUpdater.requestImmediateUpdate(context.applicationContext, force = true)
                        
                        Log.d(TAG, "Widget updates rescheduled successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error rescheduling widget updates", e)
                    } finally {
                        // Always finish the pending result to avoid ANR
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
