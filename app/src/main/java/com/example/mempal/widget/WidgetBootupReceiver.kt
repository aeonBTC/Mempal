package com.example.mempal.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Receiver to handle system boot events and package updates
 * This helps ensure widgets continue to update after device reboots
 * or when the app is updated.
 */
class WidgetBootupReceiver : BroadcastReceiver() {
    private val tag = "WidgetBootupReceiver"

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(tag, "Received system event: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(tag, "System restart detected, rescheduling widget updates")

                // Use GlobalScope as we may not have an active component
                GlobalScope.launch {
                    try {
                        // Give the system a moment to stabilize after boot/update
                        kotlinx.coroutines.delay(5000)

                        // Reschedule widget updates
                        WidgetUpdater.scheduleUpdates(context.applicationContext)

                        // Request an immediate update to refresh widgets
                        kotlinx.coroutines.delay(2000)
                        WidgetUpdater.requestImmediateUpdate(context.applicationContext, force = true)
                    } catch (e: Exception) {
                        Log.e(tag, "Error rescheduling widget updates", e)
                    }
                }
            }
        }
    }
}