package com.example.mempal.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.example.mempal.MainActivity

object WidgetUtils {
    private const val DOUBLE_TAP_TIMEOUT = 500L // milliseconds
    private var lastTapTime = 0L

    fun isDoubleTap(): Boolean {
        val currentTime = SystemClock.elapsedRealtime()
        val isDoubleTap = currentTime - lastTapTime < DOUBLE_TAP_TIMEOUT
        lastTapTime = currentTime
        return isDoubleTap
    }

    fun getLaunchAppIntent(context: Context): PendingIntent {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
} 