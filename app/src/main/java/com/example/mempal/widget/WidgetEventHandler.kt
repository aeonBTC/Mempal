package com.example.mempal.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Utility class to handle system events for widgets
 * This centralizes the logic for handling system events like screen on and power connected
 * to reduce code duplication across widget classes
 */
object WidgetEventHandler {
    private const val TAG = "WidgetEventHandler"
    
    /**
     * Process system events for the given widget class
     * @param context The application context
     * @param intent The received intent
     * @param widgetClass The widget class that received the event
     * @param refreshAction The action string that triggers widget refresh
     * @return true if event was handled, false otherwise
     */
    fun handleSystemEvent(
        context: Context, 
        intent: Intent,
        widgetClass: Class<out AppWidgetProvider>,
        refreshAction: String
    ): Boolean {
        when (intent.action) {
            refreshAction -> {
                // Handle through the widget's own onReceive
                return false
            }
            
            // Process system events as update opportunities
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "System event received for ${widgetClass.simpleName}: ${intent.action}")
                
                // Only update if it's been a while since the last update
                if (WidgetUpdater.shouldUpdate(context)) {
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val component = ComponentName(context, widgetClass)
                    val ids = appWidgetManager.getAppWidgetIds(component)
                    
                    if (ids.isNotEmpty()) {
                        Log.d(TAG, "Requesting update for ${widgetClass.simpleName} widgets")
                        WidgetUpdater.requestImmediateUpdate(context)
                    }
                } else {
                    Log.d(TAG, "Update throttled for ${widgetClass.simpleName}")
                }
                
                return true
            }
            
            else -> return false
        }
    }
} 