package com.example.mempal.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.example.mempal.api.MempoolApi
import com.example.mempal.api.WidgetNetworkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Base class for all Mempal widgets that handles common functionality:
 * - Coroutine scope management with proper lifecycle handling
 * - Widget update scheduling
 * - Network connectivity monitoring
 * - Click handling with double-tap detection
 * - Loading and error states
 * 
 * Subclasses only need to implement the data fetching and view binding logic.
 */
abstract class BaseMempalWidget : AppWidgetProvider() {
    
    companion object {
        /**
         * Manages coroutine scopes per widget class to avoid memory leaks.
         * Scopes are created on-demand and cleaned up when all widgets of that type are removed.
         */
        private val widgetScopes = Collections.synchronizedMap(mutableMapOf<Class<*>, CoroutineScope>())
        private val activeJobs = Collections.synchronizedMap(mutableMapOf<String, Job>())
        private val scopeLocks = Collections.synchronizedMap(mutableMapOf<Class<*>, Any>())
        
        private fun getScopeLock(widgetClass: Class<*>): Any {
            return scopeLocks.getOrPut(widgetClass) { Any() }
        }
        
        /**
         * Get or create a coroutine scope for the given widget class.
         * Uses class-specific scopes to allow independent lifecycle management.
         */
        fun getOrCreateScope(widgetClass: Class<*>): CoroutineScope {
            return widgetScopes[widgetClass] ?: synchronized(getScopeLock(widgetClass)) {
                widgetScopes[widgetClass] ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also {
                    widgetScopes[widgetClass] = it
                }
            }
        }
        
        /**
         * Cancel and remove the scope for a widget class.
         * Should be called when all widgets of that type are disabled.
         */
        fun cancelScope(widgetClass: Class<*>) {
            synchronized(getScopeLock(widgetClass)) {
                widgetScopes[widgetClass]?.cancel()
                widgetScopes.remove(widgetClass)
            }
        }
        
        /**
         * Cancel a specific job for a widget instance.
         */
        fun cancelJob(widgetClass: Class<*>, widgetId: Int) {
            val key = "${widgetClass.simpleName}_$widgetId"
            activeJobs[key]?.cancel()
            activeJobs.remove(key)
        }
        
        /**
         * Store a job for a widget instance.
         */
        fun storeJob(widgetClass: Class<*>, widgetId: Int, job: Job) {
            val key = "${widgetClass.simpleName}_$widgetId"
            activeJobs[key] = job
        }
        
        /**
         * Remove a job entry for a widget instance.
         */
        fun removeJob(widgetClass: Class<*>, widgetId: Int) {
            val key = "${widgetClass.simpleName}_$widgetId"
            activeJobs.remove(key)
        }
        
        /**
         * Cancel all jobs for a widget class.
         */
        fun cancelAllJobs(widgetClass: Class<*>) {
            val prefix = "${widgetClass.simpleName}_"
            synchronized(activeJobs) {
                activeJobs.keys.filter { it.startsWith(prefix) }.forEach { key ->
                    activeJobs[key]?.cancel()
                    activeJobs.remove(key)
                }
            }
        }
    }
    
    /** The refresh action string for this widget type */
    abstract val refreshAction: String
    
    /** The layout resource ID for this widget */
    abstract val layoutResId: Int
    
    /** Tag for logging */
    abstract val tag: String
    
    /** List of other widget classes to check when disabling */
    abstract val otherWidgetClasses: List<Class<out AppWidgetProvider>>
    
    /**
     * Fetch data from the API and update the RemoteViews.
     * @param context Application context
     * @param api The Mempool API instance
     * @param views The RemoteViews to update
     * @return true if data was successfully fetched and views updated, false otherwise
     */
    abstract suspend fun fetchAndUpdateData(
        context: Context,
        api: MempoolApi,
        views: RemoteViews
    ): Boolean
    
    /**
     * Set the widget views to a loading state.
     */
    abstract fun setLoadingState(views: RemoteViews)
    
    /**
     * Set the widget views to an error state.
     */
    abstract fun setErrorState(views: RemoteViews)
    
    /**
     * Get the root layout ID for click handling.
     */
    abstract val rootLayoutId: Int
    
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        getOrCreateScope(this::class.java)
        
        // Ensure services initialized
        WidgetUtils.ensureInitialized(context)
        
        // Register network connectivity monitor
        NetworkConnectivityReceiver.registerNetworkCallback(context)
        
        // Schedule updates
        WidgetUpdater.scheduleUpdates(context)
    }
    
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        
        // Only cancel updates if no other widgets are active
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val otherWidgetsExist = otherWidgetClasses.any { widgetClass ->
            val component = ComponentName(context, widgetClass)
            appWidgetManager.getAppWidgetIds(component).isNotEmpty()
        }
        
        if (!otherWidgetsExist) {
            WidgetUpdater.cancelUpdates(context)
            NetworkConnectivityReceiver.unregisterNetworkCallback(context)
        }
        
        // Always clean up this widget's resources
        cancelAllJobs(this::class.java)
        cancelScope(this::class.java)
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        // Ensure initialization on ANY event to the widget
        WidgetUtils.ensureInitialized(context)
        
        super.onReceive(context, intent)
        
        // First see if this is a system event handled by the common handler
        if (WidgetEventHandler.handleSystemEvent(context, intent, this::class.java, refreshAction)) {
            return
        }
        
        // Handle widget-specific REFRESH_ACTION
        if (intent.action == refreshAction) {
            handleRefreshAction(context)
        }
    }
    
    private fun handleRefreshAction(context: Context) {
        if (WidgetUtils.isDoubleTap()) {
            // Launch app on double tap
            val launchIntent = WidgetUtils.getLaunchAppIntent(context)
            launchIntent.send()
        } else {
            // Single tap - refresh only this widget
            Log.d(tag, "Refresh action received - updating widget")
            
            // Check for network availability before trying to update
            if (!WidgetNetworkClient.isNetworkAvailable(context)) {
                Log.d(tag, "Network unavailable, resetting tap state")
                WidgetUtils.resetTapState()
                
                // Update widget with error state
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisWidget = ComponentName(context, this::class.java)
                val widgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                
                if (widgetIds.isNotEmpty()) {
                    val views = RemoteViews(context.packageName, layoutResId)
                    setErrorState(views)
                    appWidgetManager.updateAppWidget(widgetIds[0], views)
                }
                
                return
            }
            
            // Network is available, proceed with update
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, this::class.java)
            onUpdate(context, appWidgetManager, appWidgetManager.getAppWidgetIds(thisWidget))
        }
    }
    
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Ensure services are initialized
        WidgetUtils.ensureInitialized(context)
        
        // Update each widget
        appWidgetIds.forEach { appWidgetId ->
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }
    
    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, layoutResId)
        
        // Create refresh intent - use FLAG_INCLUDE_STOPPED_PACKAGES for broadcasts
        val refreshIntent = Intent(context, this::class.java).apply {
            action = refreshAction
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId, // Use widget ID as request code for uniqueness
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Cancel any existing job for this widget
        cancelJob(this::class.java, appWidgetId)
        
        // Set loading state first
        setLoadingState(views)
        // Set click handler immediately after creating views
        views.setOnClickPendingIntent(rootLayoutId, refreshPendingIntent)
        appWidgetManager.updateAppWidget(appWidgetId, views)
        
        // Start new job
        val job = getOrCreateScope(this::class.java).launch {
            try {
                Log.d(tag, "Starting network request for widget update for ID: $appWidgetId")
                val mempoolApi = WidgetNetworkClient.getMempoolApi(context)
                
                val success = fetchAndUpdateData(context, mempoolApi, views)
                
                if (success) {
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                    Log.d(tag, "Widget $appWidgetId UI updated with new data")
                } else {
                    setErrorState(views)
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                    Log.d(tag, "Error state set for widget ID: $appWidgetId after data fetch attempt")
                }
                
            } catch (e: CancellationException) {
                Log.d(tag, "Job for widget $appWidgetId was cancelled.")
                // Re-throw to properly propagate cancellation
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Exception during widget update for ID: $appWidgetId", e)
                setErrorState(views)
                appWidgetManager.updateAppWidget(appWidgetId, views)
                Log.d(tag, "Error state set for widget ID: $appWidgetId due to exception")
            } finally {
                removeJob(this@BaseMempalWidget::class.java, appWidgetId)
                // Note: Don't reset tap state here - it would break double-tap detection
                // The tap state auto-resets after successful double-tap or 30s timeout
            }
        }
        
        storeJob(this::class.java, appWidgetId, job)
    }
}
